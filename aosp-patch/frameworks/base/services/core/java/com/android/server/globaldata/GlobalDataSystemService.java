package com.android.server.globaldata;

import android.content.Context;
import android.os.Binder;
import android.os.Bundle;
import android.os.RemoteException;
import android.os.UserHandle;
import com.android.server.SystemService;
import com.oem.globaldata.ipc.IDataObserver;
import com.oem.globaldata.ipc.IEventListener;
import com.oem.globaldata.ipc.IGlobalDataTransport;
import com.oem.globaldata.ipc.Wire;
import java.util.UUID;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.Callable;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/**
 * 在 system_server 发布 GlobalData 的 AIDL Binder 服务。Transport 先根据真实调用 UID
 * 解析地址并授权，再把后端读写及显式 grant/revoke 交给有界单线程队列；
 * 成功提交后通知数据监听者。首次 owner 登记可能发生在授权阶段。
 * EventHub 独立处理无载荷在线事件，不写入任何存储后端。
 * Binder 入口获取真实 UID 而不信任 App 自报身份；后端 I/O 由专用线程串行执行，
 * Binder 线程仍等待结果，但有界队列不会无限积压请求占用 system_server 内存。
 */
public final class GlobalDataSystemService extends SystemService {
    /** 日志消息中的类名；logcat 的统一 tag 由 TLog 指定。 */
    private static final String TAG = "GlobalDataSystemService";

    /**
     * 本次服务实例标识。revision 只在各 namespace 内递增，重启后内存后端还会归零；
     * 单靠 revision 无法区分旧服务快照，因此条件操作还要比对 epoch。
     */
    private final String epoch = UUID.randomUUID().toString();
    /**
     * 串行执行后端读写与显式 grant/revoke，避免多个 Binder 线程交错执行这些提交。
     * 队列有界以限制突发请求占用的 system_server 内存；满时明确返回 BUSY。
     */
    private final ThreadPoolExecutor storageIo = new ThreadPoolExecutor(1, 1, 0,
            TimeUnit.SECONDS, new ArrayBlockingQueue<>(128));
    /** MEMORY：进程内暂存，system_server 重启后清空。 */
    private final MemoryBackend memory = new MemoryBackend();
    /** FILE：每个 namespace 使用有界 AtomicFile 快照。 */
    private final FileBackend file = new FileBackend();
    /** DATABASE：按 scope/user 分库的 SQLite 键值存储。 */
    private final DatabaseBackend database = new DatabaseBackend();
    /** 资源 owner、签名和 grant 的持久化权限控制器。 */
    private AccessController acl;
    /** 成功写删后向单 Key 监听者发送失效通知。 */
    private DataObservers observers;
    /** 只投递在线、无载荷 topic，不参与后端存储。 */
    private EventHub events;

    public GlobalDataSystemService(Context context) { super(context); }
    /** 初始化 ACL 与在线分发器，然后以固定服务名发布 Binder。 */
    @Override public void onStart() {
        TLog.i(TAG, "starting epoch=" + epoch);
        // 先恢复 ACL，再让两个分发器引用它；Binder 只能在依赖全部就绪后对外可见。
        acl = new AccessController(getContext());
        observers = new DataObservers(acl); events = new EventHub(acl);
        publishBinderService("globaldata", new Transport());
        TLog.i(TAG, "published binder service=globaldata epoch=" + epoch);
    }
    /** 按 Wire.STORE 选择真实后端；不支持的 store 作为无效请求处理。 */
    private Backend backend(int store) {
        switch (store) {
            case Wire.MEMORY: return memory;
            case Wire.FILE: return file;
            case Wire.DATABASE: return database;
            default: throw new IllegalArgumentException("store");
        }
    }
    /** 所有 Bundle 响应附带 epoch，使 SDK 能识别服务重启并拒绝旧快照的条件更新。 */
    private Bundle status(int code) {
        TLog.d(TAG, "response status=" + code + " epoch=" + epoch);
        // 包括失败响应也带 epoch，客户端才能发现失败来自哪个服务实例。
        Bundle b = new Bundle(); b.putInt(Wire.STATUS, code); b.putString(Wire.EPOCH, epoch); return b;
    }
    /**
     * 将参数错误和执行异常收敛为稳定 Wire 状态，避免把系统内部异常细节或
     * 不可反序列化的服务端对象传给独立发布的客户端。
     */
    private Bundle failure(Exception e) {
        Throwable reason = e.getCause() == null ? e : e.getCause();
        TLog.e(TAG, "operation failed type=" + reason.getClass().getSimpleName());
        // 只把显式参数错误映射为 BAD_REQUEST；执行/磁盘异常统一隐藏为存储不可用。
        if (e instanceof IllegalArgumentException) return status(Wire.BAD_REQUEST);
        return status(Wire.STORAGE_UNAVAILABLE);
    }
    /**
     * 把读写工作交给单线程队列，并等待结果。不能在 Binder 线程中断时直接返回失败：
     * 任务可能已经在磁盘提交，否则客户端重试会造成“失败却生效”的歧义。
     * 因此继续等到确定结果，退出前再恢复中断标志。
     */
    private Bundle io(Callable<Bundle> work) {
        Future<Bundle> task;
        try {
            // 入队失败时任务没有开始，返回 BUSY 让客户端自行选择稍后重试。
            task = storageIo.submit(work);
        } catch (RejectedExecutionException full) {
            TLog.w(TAG, "storage queue full");
            return status(Wire.BUSY);
        }
        boolean interrupted = false;
        try {
            // 已入队的任务可能已经提交，不能因等待线程中断而把成功提交报告成失败。
            while (true) {
                try { return task.get(); }
                catch (InterruptedException e) { interrupted = true; }
            }
        } catch (Exception e) { return failure(e); }
        finally { if (interrupted) Thread.currentThread().interrupt(); }
    }
    /**
     * 对外实现的 AIDL 方法。Binder.getCallingUid() 是可信调用身份；user 从 UID 推导，
     * 不接受客户端在 Bundle 中指定任意 Android 用户，防止普通 App 跨用户寻址。
     * 每个入口自行处理错误与返回码。
     */
    private final class Transport extends IGlobalDataTransport.Stub {
        /** 日志中的内部类名，便于区分服务生命周期日志与 Binder 请求日志。 */
        private static final String TAG = "GlobalDataSystemService.Transport";

        /** 返回协议版本、最大 payload 和功能位；SDK 建连时先调用。 */
        @Override public Bundle getCapabilities() {
            TLog.d(TAG, "getCapabilities uid=" + Binder.getCallingUid());
            Bundle b = status(Wire.OK); b.putInt(Wire.MAJOR, Wire.PROTOCOL_MAJOR);
            b.putInt(Wire.MINOR, Wire.PROTOCOL_MINOR); b.putInt(Wire.MAX_BYTES, Wire.MAX_PAYLOAD);
            b.putLong(Wire.FEATURES, Wire.ALL_FEATURES); return b;
        }
        /**
         * 读取前先授权，再调后端；这样未经授权的调用既得不到业务值，
         * 也无法通过是否存在、revision 等响应元信息探测他人数据。
         */
        @Override public Bundle read(Bundle request) {
            // UID 来自 Binder 线程，user 是它所属的 Android 用户；started 统计完整服务处理耗时。
            int uid = Binder.getCallingUid(), user = UserHandle.getUserId(uid);
            long started = System.nanoTime();
            try {
                // 1. 解析完整地址，并把用户范围固定为 Binder 调用者所属用户。
                StoreAddress key = StoreAddress.parse(request, user);
                TLog.d(TAG, "read uid=" + uid + " " + TLog.address(key));
                // 2. 授权必须在读后端前完成，不向未授权者泄露存在性或 revision。
                int access = acl.authorize(uid, AccessController.data(key), Wire.READ, false);
                if (access != Wire.OK) {
                    TLog.w(TAG, "read denied uid=" + uid + " status=" + access);
                    return status(access);
                }
                // 3. 在存储队列中读出快照；缺失时仍返回高水位供客户端 CAS。
                return io(() -> {
                    Backend.Snapshot snapshot = backend(key.store).read(key);
                    TLog.d(TAG, "read complete " + TLog.address(key)
                            + " present=" + (snapshot.value != null) + " revision=" + snapshot.revision
                            + " ms=" + ((System.nanoTime() - started) / 1_000_000L));
                    Bundle b = status(snapshot.value == null ? Wire.NOT_FOUND : Wire.OK);
                    b.putBoolean(Wire.PRESENT, snapshot.value != null);
                    b.putLong(Wire.REVISION, snapshot.revision);
                    if (snapshot.value != null) b.putBundle(Wire.VALUE, snapshot.value.wire());
                    return b;
                });
            } catch (Exception e) { return failure(e); }
        }
        /**
         * 写入编码值：先校验地址和值，再校验 WRITE；CAS 还须匹配本服务 epoch。
         * 仅成功提交后才向 DataObservers 发送失效通知，避免监听者读到尚未提交
         * 或最终被回滚的值。
         */
        @Override public Bundle write(Bundle request, Bundle encoded, int mode, long expectedRevision) {
            int uid = Binder.getCallingUid(), user = UserHandle.getUserId(uid);
            long started = System.nanoTime();
            try {
                // 1. 在权限检查和入队前拒绝无效地址/编码，避免后端处理畸形输入。
                StoreAddress key = StoreAddress.parse(request, user);
                StoredValue value = StoredValue.parse(encoded);
                TLog.d(TAG, "write uid=" + uid + " " + TLog.address(key) + " mode=" + mode
                        + " expectedRevision=" + expectedRevision + " bytes=" + value.payload.length);
                // 2. 核对 owner 或共享写入 grant；首次真实 owner 写入可能在此持久化身份。
                int access = acl.authorize(uid, AccessController.data(key), Wire.WRITE, true);
                if (access != Wire.OK) {
                    TLog.w(TAG, "write denied uid=" + uid + " status=" + access);
                    return status(access);
                }
                // 3. 条件写入先拦截旧服务实例；后端随后再检查同一实例内的 revision。
                if (mode == Wire.IF_REVISION
                        && !epoch.equals(request.getString(Wire.EXPECTED_EPOCH))) {
                    TLog.d(TAG, "write epoch conflict " + TLog.address(key));
                    return status(Wire.CONFLICT);
                }
                // 4. 后端提交后才生成结果和通知；CONFLICT/配额失败不能触发观察回调。
                return io(() -> {
                    Backend.Change change = backend(key.store).write(key, value, mode, expectedRevision);
                    TLog.d(TAG, "write complete " + TLog.address(key) + " status=" + change.status
                            + " revision=" + change.revision + " ms=" + ((System.nanoTime() - started) / 1_000_000L));
                    Bundle b = status(change.status); b.putLong(Wire.REVISION, change.revision);
                    if (change.status == Wire.OK)
                        observers.changed(key, change.operation, change.revision, epoch);
                    return b;
                });
            } catch (Exception e) { return failure(e); }
        }
        /**
         * 删除也遵守 revision/epoch 条件检查：旧客户端的删除请求不能在服务重启
         * 或其他写入后误删新值；无条件删除由负的 expectedRevision 显式表示。
         */
        @Override public Bundle delete(Bundle request, long expectedRevision) {
            int uid = Binder.getCallingUid(), user = UserHandle.getUserId(uid);
            long started = System.nanoTime();
            try {
                // 1. 与读写共用地址规则，但删除必须独立通过 DELETE 权限。
                StoreAddress key = StoreAddress.parse(request, user);
                TLog.d(TAG, "delete uid=" + uid + " " + TLog.address(key)
                        + " expectedRevision=" + expectedRevision);
                // 2. 先授权；拒绝时不探测目标 Key 是否存在。
                int access = acl.authorize(uid, AccessController.data(key), Wire.DELETE, false);
                if (access != Wire.OK) {
                    TLog.w(TAG, "delete denied uid=" + uid + " status=" + access);
                    return status(access);
                }
                // 3. 有条件删除先核对 epoch，避免系统服务重启后版本复用。
                if (expectedRevision >= 0
                        && !epoch.equals(request.getString(Wire.EXPECTED_EPOCH))) {
                    TLog.d(TAG, "delete epoch conflict " + TLog.address(key));
                    return status(Wire.CONFLICT);
                }
                // 4. 由后端比较 revision 并提交；只有成功删除才发失效通知。
                return io(() -> {
                    Backend.Change change = backend(key.store).delete(key, expectedRevision);
                    TLog.d(TAG, "delete complete " + TLog.address(key) + " status=" + change.status
                            + " revision=" + change.revision + " ms=" + ((System.nanoTime() - started) / 1_000_000L));
                    Bundle b = status(change.status); b.putLong(Wire.REVISION, change.revision);
                    if (change.status == Wire.OK)
                        observers.changed(key, change.operation, change.revision, epoch);
                    return b;
                });
            } catch (Exception e) { return failure(e); }
        }
        /** 授权记录在存储队列中提交，避免多个 Binder 线程并行进行 ACL 文件写入。 */
        @Override public Bundle grant(Bundle resource, String targetPackage, int rights) {
            int uid = Binder.getCallingUid(), user = UserHandle.getUserId(uid);
            try {
                // 1. 仅解析资源种类/地址；真正的 owner、目标签名和 rights 校验在 ACL 中完成。
                AccessController.Resource r = AccessController.grantResource(resource, user);
                TLog.d(TAG, "grant uid=" + uid + " " + TLog.address(r)
                        + " target=" + targetPackage + " rights=" + rights);
                // 2. 在存储队列中修改并持久化授权，响应以 ACL 的实际提交结果为准。
                return io(() -> {
                    int result = acl.changeGrant(uid, r, targetPackage, rights, true);
                    TLog.d(TAG, "grant result status=" + result + " " + TLog.address(r));
                    return status(result);
                });
            } catch (Exception e) { return failure(e); }
        }
        /** owner 撤销目标包的指定权限位；不改变后端中的业务值。 */
        @Override public Bundle revoke(Bundle resource, String targetPackage, int rights) {
            int uid = Binder.getCallingUid(), user = UserHandle.getUserId(uid);
            try {
                // 与 grant 同样先解析再提交；撤销后投递线程还会重新校验订阅权限。
                AccessController.Resource r = AccessController.grantResource(resource, user);
                TLog.d(TAG, "revoke uid=" + uid + " " + TLog.address(r)
                        + " target=" + targetPackage + " rights=" + rights);
                return io(() -> {
                    int result = acl.changeGrant(uid, r, targetPackage, rights, false);
                    TLog.d(TAG, "revoke result status=" + result + " " + TLog.address(r));
                    return status(result);
                });
            } catch (Exception e) { return failure(e); }
        }
        /** 先检查 OBSERVE 再登记，避免未授权 App 借回调观察 Key 的变化时间。 */
        @Override public long subscribeData(Bundle request, IDataObserver observer) {
            int uid = Binder.getCallingUid(), user = UserHandle.getUserId(uid);
            try {
                // 1. 先校验 OBSERVE；订阅本身不触发初次 read，也不要求 READ 权限。
                StoreAddress key = StoreAddress.parse(request, user);
                TLog.d(TAG, "subscribeData uid=" + uid + " " + TLog.address(key));
                int access = acl.authorize(uid, AccessController.data(key), Wire.OBSERVE, false);
                if (access != Wire.OK) {
                    TLog.w(TAG, "subscribeData denied uid=" + uid + " status=" + access);
                    return -1;
                }
                // 2. 登记回调 Binder 和死亡监听；失败用 -1 表示，不分配可用订阅 ID。
                long id = observers.add(uid, key, observer);
                TLog.d(TAG, "subscribeData result id=" + id + " " + TLog.address(key));
                return id;
            } catch (Exception e) {
                TLog.e(TAG, "subscribeData failed type=" + e.getClass().getSimpleName());
                return -1;
            }
        }
        /** 取消当前调用 UID 创建的数据订阅，不能凭 ID 取消其他 UID 的订阅。 */
        @Override public void unsubscribeData(long id) {
            TLog.d(TAG, "unsubscribeData id=" + id + " uid=" + Binder.getCallingUid());
            observers.remove(id, Binder.getCallingUid());
        }
        /** 校验 PUBLISH 后入队；无载荷事件只作在线信号，不承担可靠状态同步。 */
        @Override public Bundle publishEvent(Bundle address) {
            int uid = Binder.getCallingUid(), user = UserHandle.getUserId(uid);
            try {
                // 1. 解析完整 topic 并校验发布权；真实 owner 首次发布可能登记签名。
                AccessController.Resource r = AccessController.event(address, user);
                TLog.d(TAG, "publishEvent uid=" + uid + " " + TLog.address(r));
                int access = acl.authorize(uid, r, Wire.PUBLISH, true);
                if (access != Wire.OK) TLog.w(TAG, "publishEvent denied uid=" + uid + " status=" + access);
                // 2. 仅在授权成功后尝试入队；返回 OK 不等待各订阅者处理完成。
                int result = access == Wire.OK ? events.publish(uid, r, epoch) : access;
                TLog.d(TAG, "publishEvent result status=" + result + " " + TLog.address(r));
                return status(result);
            } catch (Exception e) { return failure(e); }
        }
        /** 校验 SUBSCRIBE 后登记完整 topic 监听；在线事件不回放。 */
        @Override public long subscribeEvent(Bundle address, IEventListener observer) {
            int uid = Binder.getCallingUid(), user = UserHandle.getUserId(uid);
            try {
                // 先核对完整 topic 的订阅权，再登记 Binder；事件不会补发历史内容。
                AccessController.Resource r = AccessController.event(address, user);
                TLog.d(TAG, "subscribeEvent uid=" + uid + " " + TLog.address(r));
                int access = acl.authorize(uid, r, Wire.SUBSCRIBE, false);
                if (access != Wire.OK) {
                    TLog.w(TAG, "subscribeEvent denied uid=" + uid + " status=" + access);
                    return -1;
                }
                long id = events.add(uid, r, observer);
                TLog.d(TAG, "subscribeEvent result id=" + id + " " + TLog.address(r));
                return id;
            } catch (Exception e) {
                TLog.e(TAG, "subscribeEvent failed type=" + e.getClass().getSimpleName());
                return -1;
            }
        }
        /** 取消当前调用 UID 创建的事件订阅。 */
        @Override public void unsubscribeEvent(long id) {
            TLog.d(TAG, "unsubscribeEvent id=" + id + " uid=" + Binder.getCallingUid());
            events.remove(id, Binder.getCallingUid());
        }
    }
}
