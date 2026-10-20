package com.oem.globaldata.client;

import android.globaldata.GlobalDataBridge;
import android.content.Context;
import android.os.Bundle;
import android.os.IBinder;
import android.os.Looper;
import android.os.RemoteException;
import com.oem.globaldata.ipc.IDataObserver;
import com.oem.globaldata.ipc.IEventListener;
import com.oem.globaldata.ipc.IGlobalDataTransport;
import com.oem.globaldata.ipc.Wire;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.UnaryOperator;

/**
 * App 侧类型化客户端。每个 manager 独立维护串行 I/O 队列、Binder 连接和订阅列表；
 * 服务端按真实 UID 校验所有请求，客户端地址字段不能代替身份认证。
 * 异步 API 返回 Future，阻塞 API 只能在非主线程使用；用毕需 {@link #close()}。
 */
public final class GlobalDataManager implements AutoCloseable {
    /** 只保留 Application Context，避免异步订阅延长 Activity 生命周期。 */
    private final Context context;
    /** 同一 manager 的读写、授权和初始订阅按提交顺序执行。 */
    private final ExecutorService io = Executors.newSingleThreadExecutor();
    /** Binder 死亡后周期重连，不占用业务 I/O 线程。 */
    private final ScheduledExecutorService reconnect = Executors.newSingleThreadScheduledExecutor();
    /** 存活的订阅集合，供死亡通知、重连与关闭时遍历。 */
    private final List<BaseSub> subscriptions = new CopyOnWriteArrayList<>();
    /** 已验证 capability 的 Binder 代理；volatile 保证跨线程可见。 */
    private volatile IGlobalDataTransport remote;
    /** 关闭后不再连接或安排重试。 */
    private volatile boolean closed;
    /** 防止同一故障启动多个周期重试任务。 */
    private ScheduledFuture<?> retryTask;
    /** 服务进程退出时使连接与旧订阅 ID 失效。 */
    private final IBinder.DeathRecipient death = this::onDeath;

    /** 仅由 GlobalDataSdk.connect 建立，传入的是 Application Context。 */
    GlobalDataManager(Context context) { this.context = context; }

    /** 阻止同步 Binder I/O 占用主线程，避免界面卡顿或 ANR。 */
    private static void background() {
        if (Looper.myLooper() == Looper.getMainLooper())
            throw new IllegalStateException("GlobalData blocking call on main thread");
    }
    /** 首次使用或重连时获取 Bridge 的 Binder，先验证协议能力再缓存代理。 */
    private synchronized IGlobalDataTransport transport() {
        if (closed) throw new IllegalStateException("closed");
        // 仅复用仍活着的 Binder；新连接必须重新核对 capability。
        if (remote != null && remote.asBinder().isBinderAlive()) return remote;
        IBinder binder;
        // 未刷入定制镜像时可能缺少 Bridge 类，统一映射为服务不可用。
        try { binder = GlobalDataBridge.getTransportBinder(); }
        catch (LinkageError e) { binder = null; }
        if (binder == null) throw new GlobalDataException(Wire.STORAGE_UNAVAILABLE,
                "GlobalData Bridge unavailable; install the custom system image");
        IGlobalDataTransport candidate = IGlobalDataTransport.Stub.asInterface(binder);
        try {
            // 版本和完整功能位不满足要求时立即拒绝，避免在旧服务上执行错误语义。
            Bundle caps = candidate.getCapabilities();
            check(caps);
            if (caps.getInt(Wire.MAJOR) != Wire.PROTOCOL_MAJOR)
                throw new GlobalDataException(Wire.UNSUPPORTED, "protocol major mismatch");
            if (caps.getInt(Wire.MINOR) < Wire.PROTOCOL_MINOR)
                throw new GlobalDataException(Wire.UNSUPPORTED, "server protocol minor too old");
            if (caps.getInt(Wire.MAX_BYTES) < 1)
                throw new GlobalDataException(Wire.UNSUPPORTED, "invalid server capabilities");
            if ((caps.getLong(Wire.FEATURES) & Wire.ALL_FEATURES) != Wire.ALL_FEATURES)
                throw new GlobalDataException(Wire.UNSUPPORTED, "required capability missing");
        } catch (RemoteException e) { throw failed(e); }
        // 先登记死亡通知，再让其他线程看到此代理。
        try { binder.linkToDeath(death, 0); }
        catch (RemoteException e) { throw failed(e); }
        remote = candidate;
        return candidate;
    }
    /** 将服务端非 OK 的 Bundle 统一变成带状态码的异常。 */
    private static void check(Bundle result) {
        if (result == null) throw new GlobalDataException(Wire.STORAGE_UNAVAILABLE, "null response");
        // 缺少 status 本身是异常响应，不能误判为成功。
        int status = result.getInt(Wire.STATUS, Wire.BAD_REQUEST);
        if (status != Wire.OK)
            throw new GlobalDataException(status, "GlobalData status " + status);
    }
    /** 保留 Binder 异常链并统一映射为 STORAGE_UNAVAILABLE。 */
    private static GlobalDataException failed(RemoteException e) {
        return new GlobalDataException(Wire.STORAGE_UNAVAILABLE, "Binder transport failed", e);
    }
    /** Binder 死亡可能发生在任意 Binder 线程，旧订阅 ID 已不能继续使用。 */
    private void onDeath() {
        synchronized (this) { remote = null; }
        for (BaseSub sub : subscriptions) {
            // 重连后必须向新服务实例重新注册。
            sub.registeredBinder = null;
            sub.resync();
        }
        scheduleRetry();
    }
    /** 每两秒重试，恢复数据订阅后主动读当前值；离线事件不会补发。 */
    private synchronized void scheduleRetry() {
        if (closed || (retryTask != null && !retryTask.isDone())) return;
        retryTask = reconnect.scheduleWithFixedDelay(() -> {
            if (closed) return;
            try {
                IGlobalDataTransport t = transport();
                for (BaseSub sub : subscriptions) {
                    try {
                        sub.register(t);
                        // 数据通知只提示变化，重连后需读取权威当前值。
                        if (sub instanceof DataSub<?>) io.execute(((DataSub<?>) sub)::refresh);
                    } catch (GlobalDataException e) {
                        if (e.status == Wire.PERMISSION_DENIED) {
                            // 授权失效的订阅不无限重试；交给业务决定是否重新申请。
                            sub.notifyFailure(e);
                            sub.close();
                        } else throw e;
                    }
                }
                // 所有剩余订阅恢复后取消周期任务。
                synchronized (GlobalDataManager.this) {
                    retryTask.cancel(false); retryTask = null;
                }
            }
            catch (RuntimeException ignored) { /* 服务可能尚未启动，保留周期重试。 */ }
        }, 1, 2, TimeUnit.SECONDS);
    }

    /**
     * 在本 manager 的 I/O 队列读取并用 Key 的 Codec 解码。
     * @param key 完整的类型化数据地址
     * @return 异步快照；不存在时正常返回 present=false
     */
    public <T> CompletableFuture<DataSnapshot<T>> read(DataKey<T> key) {
        return CompletableFuture.supplyAsync(() -> readBlocking(key), io);
    }
    /**
     * 同步读取；只允许后台线程，调用方需自行安排生命周期和异常处理。
     * @param key 完整的类型化数据地址
     * @return 存在或不存在的当前快照
     * @throws IllegalStateException 在主线程调用
     */
    public <T> DataSnapshot<T> readBlocking(DataKey<T> key) {
        background();
        try {
            Bundle result = transport().read(key.wire());
            if (result == null) throw new GlobalDataException(Wire.STORAGE_UNAVAILABLE, "null response");
            // 不存在是正常数据状态，保留服务端返回的版本高水位供 CAS 使用。
            if (result.getInt(Wire.STATUS, Wire.BAD_REQUEST) != Wire.NOT_FOUND) check(result);
            boolean present = result.getBoolean(Wire.PRESENT);
            T value = null;
            if (present) {
                Bundle encoded = result.getBundle(Wire.VALUE);
                // schema 不同就拒绝解码，不能把同一 Key 的其他业务格式硬转成 T。
                if (encoded == null || !key.codec.schemaId().equals(encoded.getString(Wire.SCHEMA_ID)))
                    throw new GlobalDataException(Wire.UNSUPPORTED, "schema mismatch");
                value = key.codec.decode(encoded.getByteArray(Wire.PAYLOAD),
                        encoded.getInt(Wire.SCHEMA_VERSION));
            }
            // 即使值不存在，revision 和 epoch 仍描述本次读取的服务端状态。
            return new DataSnapshot<>(present, value, result.getLong(Wire.REVISION),
                    result.getString(Wire.EPOCH));
        // 传输失败先使旧连接失效，再交给业务处理本次失败。
        } catch (RemoteException e) { onDeath(); throw failed(e); }
        catch (GlobalDataException e) { throw e; }
        catch (Exception e) { throw new GlobalDataException(Wire.BAD_REQUEST, "decode failed", e); }
    }
    /**
     * 异步 upsert：Key 不存在时创建，已存在时覆盖。
     * @param key 数据地址
     * @param value 非 null 值；删除请使用 remove
     * @return 写入完成后的 revision 和服务 epoch
     */
    public <T> CompletableFuture<WriteResult> put(DataKey<T> key, T value) {
        return CompletableFuture.supplyAsync(() -> putBlocking(key, value), io);
    }
    /**
     * 与 put 相同的同步版本，只能在后台线程使用。
     * @param key 数据地址
     * @param value 非 null 值
     * @return 写入结果
     * @throws IllegalStateException 在主线程调用
     */
    public <T> WriteResult putBlocking(DataKey<T> key, T value) {
        return writeBlocking(key, value, Wire.UPSERT, -1, null);
    }
    /**
     * 只在 Key 当前不存在时写入，已存在则异常完成并给出 CONFLICT。
     * @param key 数据地址
     * @param value 非 null 待写值
     * @return 创建后的版本结果
     */
    public <T> CompletableFuture<WriteResult> putIfAbsent(DataKey<T> key, T value) {
        return CompletableFuture.supplyAsync(() -> writeBlocking(key, value, Wire.IF_ABSENT, -1, null), io);
    }
    /**
     * CAS 写入：服务端同时比较预期 revision 和服务 epoch，防止旧值覆盖新值。
     * @param key 与 expected 对应的同一个数据地址
     * @param value 非 null 新值
     * @param expected 先前 read 获得的快照，不能手写旧版本号代替
     * @return 条件成立时的写入结果；冲突时 Future 以 CONFLICT 异常完成
     */
    public <T> CompletableFuture<WriteResult> putIfRevision(
            DataKey<T> key, T value, DataSnapshot<?> expected) {
        // 快照没有 epoch 时，服务重启后的旧 revision 可能与新版本偶然相同。
        if (expected == null || expected.serviceEpoch == null)
            throw new IllegalArgumentException("CAS requires a snapshot with service epoch");
        return CompletableFuture.supplyAsync(() -> writeBlocking(key, value, Wire.IF_REVISION,
                expected.revision, expected.serviceEpoch), io);
    }
    /** 将三种写模式汇合为编码、服务调用与结果解析的统一后台路径。 */
    private <T> WriteResult writeBlocking(DataKey<T> key, T value, int mode,
            long revision, String expectedEpoch) {
        background();
        // null 不表示删除，避免 Codec 和服务端对“空值”产生不同解释。
        if (value == null) throw new IllegalArgumentException("null values are not supported; remove the key");
        try {
            byte[] bytes = key.codec.encode(value);
            // SDK 先拦截超大值；服务端也会独立验证，不能只依赖客户端。
            if (bytes == null || bytes.length > Wire.MAX_PAYLOAD)
                throw new GlobalDataException(Wire.QUOTA_EXCEEDED, "value exceeds 64 KiB");
            // schema 与 payload 同时落在服务端，读取时 Codec 可据版本兼容旧值。
            Bundle encoded = new Bundle(); encoded.putString(Wire.SCHEMA_ID, key.codec.schemaId());
            encoded.putInt(Wire.SCHEMA_VERSION, key.codec.schemaVersion());
            encoded.putString(Wire.ENCODING, key.codec.encoding());
            encoded.putByteArray(Wire.PAYLOAD, bytes);
            Bundle request = key.wire();
            // CAS 除 revision 外还比较服务实例，防止 system_server 重启后的 ABA。
            if (mode == Wire.IF_REVISION) request.putString(Wire.EXPECTED_EPOCH, expectedEpoch);
            // 持久化后端确认提交才返回 OK；服务端负责发数据通知。
            Bundle result = transport().write(request, encoded, mode, revision); check(result);
            return new WriteResult(result.getLong(Wire.REVISION), result.getString(Wire.EPOCH));
        } catch (RemoteException e) { onDeath(); throw failed(e); }
        catch (GlobalDataException e) { throw e; }
        catch (Exception e) { throw new GlobalDataException(Wire.BAD_REQUEST, "encode failed", e); }
    }
    /**
     * 便利的“读取→计算→CAS”修改，遇版本冲突最多重试三次。
     * @param key 要修改的地址
     * @param updater 旧值到新值的函数；Key 不存在时旧值为 null，且可能执行多次，应避免副作用
     * @return 成功提交的新版本；多次冲突后返回 CONFLICT
     */
    public <T> CompletableFuture<WriteResult> update(DataKey<T> key, UnaryOperator<T> updater) {
        return CompletableFuture.supplyAsync(() -> {
            for (int i = 0; i < 3; i++) {
                DataSnapshot<T> old = readBlocking(key);
                T next = updater.apply(old.value);
                // 只对冲突重新读取；权限、编码或服务错误应直接交给业务处理。
                try { return writeBlocking(key, next, Wire.IF_REVISION,
                        old.revision, old.serviceEpoch); }
                catch (GlobalDataException e) { if (e.status != Wire.CONFLICT) throw e; }
            }
            throw new GlobalDataException(Wire.CONFLICT, "CAS retry limit reached");
        }, io);
    }
    /**
     * 无条件删除；不存在视为正常完成，成功删除时推进 namespace revision。
     * @param key 要删除的地址
     * @return 删除后的版本，或不存在时的当前版本高水位
     */
    public CompletableFuture<WriteResult> remove(DataKey<?> key) {
        return CompletableFuture.supplyAsync(() -> removeBlocking(key, -1, null), io);
    }
    /**
     * 仅在快照的 epoch/revision 仍匹配时删除。
     * @param key 与 expected 对应的数据地址
     * @param expected 先前读取的快照
     * @return 删除后的版本；不存在时高层同样按正常结果处理
     */
    public CompletableFuture<WriteResult> removeIfRevision(DataKey<?> key, DataSnapshot<?> expected) {
        if (expected == null || expected.serviceEpoch == null)
            throw new IllegalArgumentException("CAS delete requires a snapshot with service epoch");
        return CompletableFuture.supplyAsync(() -> removeBlocking(key,
                expected.revision, expected.serviceEpoch), io);
    }
    /** 删除请求共用的后台实现；-1 表示不检查 revision。 */
    private WriteResult removeBlocking(DataKey<?> key, long expectedRevision,
            String expectedEpoch) {
        background();
        try {
            Bundle request = key.wire();
            if (expectedRevision >= 0) request.putString(Wire.EXPECTED_EPOCH, expectedEpoch);
            Bundle result = transport().delete(request, expectedRevision);
            if (result == null) throw new GlobalDataException(Wire.STORAGE_UNAVAILABLE, "null response");
            // 重复删除不存在的 Key 不当作业务错误，但保留服务端版本高水位。
            if (result.getInt(Wire.STATUS, Wire.BAD_REQUEST) != Wire.NOT_FOUND) check(result);
            return new WriteResult(result.getLong(Wire.REVISION), result.getString(Wire.EPOCH));
        } catch (RemoteException e) { onDeath(); throw failed(e); }
    }
    /** grant/revoke 共用的后台 Binder 路径；服务端根据真实调用 UID 判断是否 owner。 */
    private void aclBlocking(Bundle resource, String targetPackage, int rights, boolean grant) {
        background();
        try { check(grant ? transport().grant(resource, targetPackage, rights)
                          : transport().revoke(resource, targetPackage, rights)); }
        catch (RemoteException e) { onDeath(); throw failed(e); }
    }
    /** 单目标授权和撤销都在 manager 的串行 I/O 队列执行。 */
    private CompletableFuture<Void> acl(Bundle resource, String targetPackage, int rights, boolean grant) {
        return CompletableFuture.runAsync(() -> aclBlocking(resource, targetPackage, rights, grant), io);
    }
    /**
     * 给目标包授予数据权限。传入 Key 只用于定位 scope/owner/namespace；
     * 授权覆盖该 namespace 下三个 Store 的所有 Key，目标包须已安装。
     * @param key 指定授权分组的任意 DataKey
     * @param targetPackage 被授权包名，服务端绑定其当前签名
     * @param rights Wire.READ/WRITE/DELETE/OBSERVE 的按位或结果
     * @return 授权持久化成功时正常完成的 Future
     */
    public CompletableFuture<Void> grantData(DataKey<?> key, String targetPackage, int rights) {
        // 资源类型显式标明为 namespace，而不是对单个 key 授权。
        Bundle b = key.wire(); b.putInt(Wire.RESOURCE, Wire.RESOURCE_DATA);
        return acl(b, targetPackage, rights, true);
    }
    /**
     * 将同一组数据权限授予多个 App，适用于一个 namespace 需要共享给多个目标包的场景。
     * 此方法可从主线程调用，在 manager 的 I/O 队列按集合迭代顺序逐个授权；重复包名只调用一次。
     * 这不是原子事务：某个包授权失败时停止处理后续包，先前成功的授权仍然有效。
     * Future 由后台线程完成；界面更新需切回主线程，manager 用毕仍需关闭。
     * @param key 指定 scope/owner/namespace 的任意 DataKey，同组所有 Store 和 Key 共用授权
     * @param targetPackages 非空目标包集合；目标 App 均须已安装，调用期间修改原集合不影响已排队任务
     * @param rights 每个目标包相同的数据权限位，使用 Wire.READ/WRITE/DELETE/OBSERVE 按位或
     * @return 全部目标包授权成功后正常完成的 Future；服务端拒绝时异常包含出错包名和状态码
     * @throws IllegalArgumentException 集合为空或包含空包名
     */
    public CompletableFuture<Void> grantDataToApps(DataKey<?> key, Collection<String> targetPackages,
                                                   int rights) {
        if (targetPackages == null || targetPackages.isEmpty())
            throw new IllegalArgumentException("targetPackages must not be empty");
        // 保留调用方顺序并去重，避免同一包重复写入持久化 ACL。
        List<String> packages = new ArrayList<>(new LinkedHashSet<>(targetPackages));
        for (String targetPackage : packages) {
            if (targetPackage == null || targetPackage.isBlank())
                throw new IllegalArgumentException("targetPackages contains an empty package name");
        }
        Bundle resource = key.wire(); resource.putInt(Wire.RESOURCE, Wire.RESOURCE_DATA);
        // 整批操作占用同一个串行任务，调用方可以等待这个 Future 后再写入共享数据。
        return CompletableFuture.runAsync(() -> {
            for (String targetPackage : packages) {
                try { aclBlocking(resource, targetPackage, rights, true); }
                catch (GlobalDataException e) {
                    throw new GlobalDataException(e.status,
                            "grantData failed for " + targetPackage + ": " + e.getMessage(), e);
                }
            }
        }, io);
    }
    /**
     * 从目标包移除数据 namespace 上的指定位，未移除的权限继续有效。
     * @param key 指定 scope/owner/namespace 的地址
     * @param targetPackage 被撤销授权的包
     * @param rights 需要移除的 Wire 数据权限位
     * @return 撤销持久化成功时正常完成的 Future
     */
    public CompletableFuture<Void> revokeData(DataKey<?> key, String targetPackage, int rights) {
        Bundle b = key.wire(); b.putInt(Wire.RESOURCE, Wire.RESOURCE_DATA);
        return acl(b, targetPackage, rights, false);
    }
    /**
     * 针对完整 topic 授予发布和/或订阅权限，独立于数据 namespace 的授权。
     * @param topic 事件地址
     * @param targetPackage 被授权包名
     * @param rights Wire.PUBLISH/SUBSCRIBE 的按位或结果
     * @return 授权结果 Future
     */
    public CompletableFuture<Void> grantEvent(EventTopic topic, String targetPackage, int rights) {
        Bundle b = topic.wire(); b.putInt(Wire.RESOURCE, Wire.RESOURCE_EVENT);
        return acl(b, targetPackage, rights, true);
    }
    /**
     * 撤销目标包在完整 topic 上的指定位。
     * @param topic 事件地址
     * @param targetPackage 被撤销授权的包
     * @param rights 需要移除的事件权限位
     * @return 撤销结果 Future
     */
    public CompletableFuture<Void> revokeEvent(EventTopic topic, String targetPackage, int rights) {
        Bundle b = topic.wire(); b.putInt(Wire.RESOURCE, Wire.RESOURCE_EVENT);
        return acl(b, targetPackage, rights, false);
    }
    /**
     * 先注册单个 Key 的观察者，再主动读取当前值；后续通知也触发重新读取。
     * @param key 要观察的类型化地址
     * @param callbackExecutor 执行 DataListener 回调的 Executor，可传主线程 Executor
     * @param listener 初始值、后续变化、重同步和错误的接收方
     * @return 成功注册后的可关闭订阅；回调可能在 Future 完成前排入执行器
     */
    public <T> CompletableFuture<Subscription> observeAndRead(DataKey<T> key,
            Executor callbackExecutor, DataListener<T> listener) {
        return CompletableFuture.supplyAsync(() -> {
            DataSub<T> sub = new DataSub<>(key, callbackExecutor, listener);
            // 先纳入 manager 生命周期，Binder 死亡时才能一起恢复。
            subscriptions.add(sub);
            // 先注册再读取，缩小初始读取与后续变化之间的漏通知窗口。
            try { sub.register(transport()); sub.refresh(); return sub; }
            catch (RuntimeException e) { subscriptions.remove(sub); throw e; }
        }, io);
    }
    /** @return 复用当前 manager 的无载荷事件门面，不新建 Binder 服务 */
    public GlobalEventBus events() { return new GlobalEventBus(this); }
    /**
     * 固定 Memory Store 地址前缀，简化多次读写同一 namespace 的代码。
     * @param scope 用户/设备作用域
     * @param owner owner 包名
     * @param namespace 数据授权分组
     * @return Memory 便利门面
     */
    public GlobalStore memory(int scope, String owner, String namespace) {
        return new GlobalStore(this, Wire.MEMORY, scope, owner, namespace);
    }
    /**
     * 固定 File Store 地址前缀，服务端按 scope 把值保存到 DE/CE 目录。
     * @param scope 用户/设备作用域
     * @param owner owner 包名
     * @param namespace 数据授权分组
     * @return File 便利门面
     */
    public GlobalStore file(int scope, String owner, String namespace) {
        return new GlobalStore(this, Wire.FILE, scope, owner, namespace);
    }
    /**
     * 固定 Database Store 地址前缀，服务端在对应用户/作用域的 SQLite 中读写。
     * @param scope 用户/设备作用域
     * @param owner owner 包名
     * @param namespace 数据授权分组
     * @return Database 便利门面
     */
    public GlobalStore database(int scope, String owner, String namespace) {
        return new GlobalStore(this, Wire.DATABASE, scope, owner, namespace);
    }
    /** EventBus.post 的实际后台 Binder 调用；成功表示接受排队，事件不落盘。 */
    CompletableFuture<Void> post(EventTopic topic) {
        return CompletableFuture.runAsync(() -> {
            try { check(transport().publishEvent(topic.wire())); }
            catch (RemoteException e) { onDeath(); throw failed(e); }
        }, io);
    }
    /** 注册 EventBus 的在线 topic 监听；与数据订阅共用重连和关闭逻辑。 */
    CompletableFuture<Subscription> subscribe(EventTopic topic, Executor callbackExecutor,
                                               java.util.function.Consumer<EventTopic> listener) {
        return CompletableFuture.supplyAsync(() -> {
            EventSub sub = new EventSub(topic, callbackExecutor, listener);
            // 先纳入 manager，服务进程死亡时才会重新注册。
            subscriptions.add(sub);
            try { sub.register(transport()); return sub; }
            catch (RuntimeException e) { subscriptions.remove(sub); throw e; }
        }, io);
    }
    /** 数据与事件订阅共用的 ID、Binder 归属、关闭和重连状态。 */
    private abstract class BaseSub implements Subscription {
        /** 当前服务实例分配的订阅 ID；服务重启后会被替换。 */
        volatile long id;
        /** 停止标志使已排队但尚未投递的回调不再触达业务代码。 */
        volatile boolean stopped;
        /** ID 所属的 Binder，防止拿旧 ID 去新服务实例退订。 */
        volatile IBinder registeredBinder;
        /** 把本订阅注册到指定服务实例，重复注册同一实例应无动作。 */
        abstract void register(IGlobalDataTransport transport);
        /** 仅数据订阅需通知 UI 重同步；事件订阅保持空实现。 */
        void resync() {}
        /** 从 manager 生命周期移除订阅，并在 I/O 线程向原服务实例退订。 */
        @Override public void close() {
            if (stopped) return;
            stopped = true; subscriptions.remove(this);
            io.execute(() -> {
                IGlobalDataTransport t = remote;
                // 服务已换实例时旧 ID 自动失效，不向新实例发送退订。
                if (t != null && registeredBinder == t.asBinder()) unsubscribe(t);
            });
        }
        /** 数据订阅可把权限失效通知业务；事件订阅没有错误回调。 */
        void notifyFailure(Throwable error) {}
        /** 子类选择正确的 Data 或 Event 退订方法。 */
        abstract void unsubscribe(IGlobalDataTransport t);
    }
    /** 一个 Key 对应一个远端观察者，收到通知后读取服务端当前快照。 */
    private final class DataSub<T> extends BaseSub {
        /** Key、串行回调执行器和业务监听者绑定在订阅生命周期内。 */
        final DataKey<T> key; final Executor callback; final DataListener<T> listener;
        /** oneway Binder 回调不解析业务值，只把读取工作交给 I/O 队列。 */
        final IDataObserver observer = new IDataObserver.Stub() {
            /** 通知是变化提示；重新读取可合并连续写入并取得权威当前值。 */
            @Override public void onDataChanged(Bundle event) {
                if (!stopped) io.execute(DataSub.this::refresh);
            }
            /** 通知队列溢出时先提示业务，再主动读取补齐最新状态。 */
            @Override public void onResyncRequired(String epoch) {
                resync();
                io.execute(DataSub.this::refresh);
            }
        };
        /** 每个订阅单独包装 SerialExecutor，保持它自己的回调顺序。 */
        DataSub(DataKey<T> key, Executor callback, DataListener<T> listener) {
            this.key = key; this.callback = new SerialExecutor(callback); this.listener = listener;
        }
        /** 注册单个 Key；远端只返回 ID，初始快照由 refresh 单独读取。 */
        @Override void register(IGlobalDataTransport t) {
            if (stopped) return;
            if (registeredBinder == t.asBinder()) return;
            try { id = t.subscribeData(key.wire(), observer); }
            catch (RemoteException e) { throw failed(e); }
            // AIDL 只返回 -1 而非详细状态，当前客户端统一映射为权限拒绝。
            if (id <= 0) throw new GlobalDataException(Wire.PERMISSION_DENIED, "data subscription rejected");
            registeredBinder = t.asBinder();
        }
        /** 在后台读当前快照，再按调用方指定的 Executor 交付业务回调。 */
        void refresh() {
            if (stopped) return;
            try {
                DataSnapshot<T> snapshot = readBlocking(key);
                callback.execute(() -> { if (!stopped) listener.onChanged(snapshot); });
            } catch (Throwable e) {
                // 读取失败不会把旧值当作新值通知业务。
                callback.execute(() -> { if (!stopped) listener.onError(e); });
            }
        }
        /** 服务重启或通知溢出时提醒界面当前显示可能暂时过期。 */
        @Override void resync() {
            if (!stopped) callback.execute(() -> {
                if (!stopped) listener.onResyncRequired();
            });
        }
        /** 重连后若权限被撤销，将失败交给数据监听者。 */
        @Override void notifyFailure(Throwable error) {
            callback.execute(() -> listener.onError(error));
        }
        /** 只退订当前 ID；Binder 已死亡时失败可忽略。 */
        @Override void unsubscribe(IGlobalDataTransport t) {
            try { t.unsubscribeData(id); } catch (RemoteException ignored) {}
        }
    }
    /** 在线 topic 订阅，不保存事件、也不在重连后重放离线事件。 */
    private final class EventSub extends BaseSub {
        /** 完整 topic 地址与按订阅排序的回调执行器。 */
        final EventTopic topic; final Executor callback;
        /** 业务只接收 EventTopic，不接收来自服务端的任意业务对象。 */
        final java.util.function.Consumer<EventTopic> listener;
        /** oneway Binder 回调把本地已约定的 topic 投递给 App。 */
        final IEventListener observer = new IEventListener.Stub() {
            /** 服务端事件 Bundle 仅作到达信号，不包含业务 payload。 */
            @Override public void onEvent(Bundle event) {
                if (!stopped) callback.execute(() -> {
                    if (!stopped) listener.accept(topic);
                });
            }
        };
        /** 为本订阅创建独立的串行回调队列。 */
        EventSub(EventTopic topic, Executor callback, java.util.function.Consumer<EventTopic> listener) {
            this.topic = topic; this.callback = new SerialExecutor(callback); this.listener = listener;
        }
        /** 在新服务实例重新注册 topic；旧服务期间的事件不会补发。 */
        @Override void register(IGlobalDataTransport t) {
            if (stopped) return;
            if (registeredBinder == t.asBinder()) return;
            try { id = t.subscribeEvent(topic.wire(), observer); }
            catch (RemoteException e) { throw failed(e); }
            // AIDL 失败只返回 -1，无法仅从这里区分权限和数量限制。
            if (id <= 0) throw new GlobalDataException(Wire.PERMISSION_DENIED, "event subscription rejected");
            registeredBinder = t.asBinder();
        }
        /** 仅对注册该 ID 的服务实例退订。 */
        @Override void unsubscribe(IGlobalDataTransport t) {
            try { t.unsubscribeEvent(id); } catch (RemoteException ignored) {}
        }
    }
    /** 停止重连和新任务，关闭所有订阅并解除 Binder 死亡监听。 */
    @Override public void close() {
        closed = true;
        // 先排入各订阅的退订动作，再停止 I/O 执行器接收新任务。
        for (BaseSub sub : subscriptions) sub.close();
        io.shutdown(); reconnect.shutdownNow();
        IGlobalDataTransport t = remote;
        if (t != null) t.asBinder().unlinkToDeath(death, 0);
    }
}
