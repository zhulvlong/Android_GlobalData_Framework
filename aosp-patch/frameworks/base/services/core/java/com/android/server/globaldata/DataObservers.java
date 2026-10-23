package com.android.server.globaldata;

import android.os.Bundle;
import android.os.IBinder;
import android.os.RemoteException;
import com.oem.globaldata.ipc.IDataObserver;
import com.oem.globaldata.ipc.Wire;
import java.util.Map;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 单 Key 变化通知中心。后端成功提交后由系统服务调用 changed，通知只携带地址、
 * 操作码和版本，客户端应重新读取实际值。投递使用独立有界队列，不持有后端存储锁。
 * 只发失效提示是为了避免慢客户端收到排队已久的旧值，也减少跨进程复制业务 payload；
 * 有界队列保护 system_server，溢出时用“重新同步”提示恢复客户端视图。
 */
final class DataObservers {
    /** 日志二级标识。 */
    private static final String TAG = "DataObservers";

    /** 单调生成订阅 ID，避免同一服务实例内取消旧订阅时误指向新订阅。 */
    private final AtomicLong next = new AtomicLong(1);
    /** 订阅 ID -> 回调 Binder 和身份信息，可供投递线程并发遍历。 */
    private final Map<Long, Entry> entries = new ConcurrentHashMap<>();
    /** 单线程顺序投递，最多等待 256 个通知任务；溢出时提示客户端重同步。 */
    private final ThreadPoolExecutor queue = new ThreadPoolExecutor(1, 1, 0, TimeUnit.SECONDS,
            new ArrayBlockingQueue<>(256));
    /**
     * 注册时的权限可能在排队期间被撤销；投递前再次检查，
     * 避免已撤权客户端继续根据变化时间推测 Key 的活动。
     */
    private final AccessController acl;
    DataObservers(AccessController acl) { this.acl = acl; }
    /** Binder 死亡时自动清理登记，避免客户端崩溃后持续占用订阅配额与回调资源。 */
    private final class Entry implements IBinder.DeathRecipient {
        /** 内部类日志标识。 */
        private static final String TAG = "DataObservers.Entry";

        /** id 是取消订阅句柄；uid 是创建者；key 为完整监听地址。 */
        final long id; final int uid; final StoreAddress key; final IDataObserver observer;
        Entry(long id, int uid, StoreAddress key, IDataObserver observer) {
            this.id = id; this.uid = uid; this.key = key; this.observer = observer;
        }
        /** 客户端进程退出后移除监听，避免继续向失效 Binder 投递。 */
        @Override public void binderDied() {
            TLog.w(TAG, "listener binder died id=" + id + " uid=" + uid);
            remove(id, uid);
        }
    }
    /** 登记一个 Key 的 observer，检查空回调和每 UID 的 64 个订阅上限。 */
    long add(int uid, StoreAddress key, IDataObserver observer) {
        TLog.d(TAG, "register uid=" + uid + " " + TLog.address(key));
        // 1. 拒绝空 Binder，并限制单 UID 的订阅数，避免耗尽 system_server 回调资源。
        if (observer == null) {
            TLog.w(TAG, "register rejected null listener uid=" + uid);
            return -1;
        }
        long count = entries.values().stream().filter(e -> e.uid == uid).count();
        if (count >= 64) {
            TLog.w(TAG, "register quota uid=" + uid);
            return -1;
        }
        // 2. 先挂死亡监听，再发布到 entries；客户端已经退出时不能留下僵尸订阅。
        Entry entry = new Entry(next.getAndIncrement(), uid, key, observer);
        try { observer.asBinder().linkToDeath(entry, 0); }
        catch (RemoteException e) {
            TLog.w(TAG, "register binder unavailable uid=" + uid);
            return -1;
        }
        // 3. 只有 Binder 可监听死亡时才让投递线程看见该订阅。
        entries.put(entry.id, entry);
        TLog.d(TAG, "registered id=" + entry.id + " uid=" + uid + " " + TLog.address(key));
        return entry.id;
    }
    /** 以 UID 校验取消操作，防止其他 App 猜到订阅 ID 后关闭别人的监听。 */
    void remove(long id, int uid) {
        TLog.d(TAG, "remove request id=" + id + " uid=" + uid);
        // 先核对创建者，再移除映射并解绑死亡回调；未知 ID 直接视为已取消。
        Entry entry = entries.get(id);
        if (entry == null || entry.uid != uid) return;
        entries.remove(id); entry.observer.asBinder().unlinkToDeath(entry, 0);
        TLog.d(TAG, "removed id=" + id + " uid=" + uid);
    }
    /**
     * 成功提交后的通知入口。按完整地址筛选订阅；队列满时直接发送重同步提示，
     * 因为丢掉任一失效通知后客户端缓存可能长期停留在旧版本，必须主动重新读取。
     */
    void changed(StoreAddress key, int operation, long revision, String epoch) {
        TLog.d(TAG, "change " + TLog.address(key) + " operation=" + operation
                + " revision=" + revision + " epoch=" + epoch);
        // 1. 只筛选完全相同的 Store/Scope/User/Owner/Namespace/Key，不广播其他地址。
        for (Entry entry : entries.values()) {
            if (!entry.key.equals(key)) continue;
            try {
                // 2. 投递放入独立队列，避免慢客户端回调阻塞存储提交路径。
                queue.execute(() -> {
                    // 3. 排队期间授权可能已被撤销，真正发送前再检查一次。
                    if (acl.check(entry.uid, AccessController.data(key), Wire.OBSERVE) != Wire.OK) {
                        TLog.w(TAG, "delivery revoked id=" + entry.id + " uid=" + entry.uid);
                        remove(entry.id, entry.uid); return;
                    }
                    // 4. 只发送元信息；客户端用 revision/epoch 判断是否需要重新读取。
                    Bundle event = new Bundle(); event.putInt(Wire.STORE, key.store);
                    event.putInt(Wire.SCOPE, key.scope); event.putString(Wire.OWNER, key.owner);
                    event.putString(Wire.NAMESPACE, key.namespace); event.putString(Wire.KEY, key.key);
                    event.putInt(Wire.OPERATION, operation); event.putLong(Wire.REVISION, revision);
                    event.putString(Wire.EPOCH, epoch);
                    try {
                        entry.observer.onDataChanged(event);
                        TLog.d(TAG, "delivered id=" + entry.id + " revision=" + revision);
                    }
                    catch (RemoteException e) {
                        // Binder 失败不回滚已提交的数据，只清理失效订阅。
                        TLog.w(TAG, "delivery binder failure id=" + entry.id);
                        remove(entry.id, entry.uid);
                    }
                });
            } catch (RejectedExecutionException overflow) {
                // 5. 队列已丢失普通通知，直接提示客户端全量重读以修复缓存视图。
                TLog.w(TAG, "queue overflow id=" + entry.id + " uid=" + entry.uid);
                try { entry.observer.onResyncRequired(epoch); }
                catch (RemoteException e) {
                    TLog.w(TAG, "resync binder failure id=" + entry.id);
                    remove(entry.id, entry.uid);
                }
            }
        }
    }
}
