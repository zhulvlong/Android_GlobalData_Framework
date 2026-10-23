package com.android.server.globaldata;

import android.os.Bundle;
import android.os.IBinder;
import android.os.RemoteException;
import com.oem.globaldata.ipc.IEventListener;
import com.oem.globaldata.ipc.Wire;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 无载荷 topic 的在线事件中心。发布成功只表示进入有界队列；事件不会持久化、
 * 不补发离线消息，也不保证每个订阅者均已收到。
 * 此处只承载“请刷新”等瞬时信号；需要断线后可恢复的业务状态应写入数据后端，
 * 避免把无持久化保证的事件误用作可靠消息队列。
 */
final class EventHub {
    /** 日志二级标识。 */
    private static final String TAG = "EventHub";

    /** 订阅和投递时检查当前 ACL，授权撤销后停止向该订阅发送。 */
    private final AccessController acl;
    /** 本次服务实例内的订阅 ID 生成器。 */
    private final AtomicLong next = new AtomicLong(1);
    /** 订阅 ID -> topic、UID 和回调 Binder。 */
    private final Map<Long, Entry> entries = new ConcurrentHashMap<>();
    /**
     * 投递与发布请求解耦，慢接收者不直接占用发布线程；
     * 队列有界，防止事件洪峰在 system_server 中积压无限任务。
     */
    private final ThreadPoolExecutor queue = new ThreadPoolExecutor(1, 1, 0, TimeUnit.SECONDS,
            new ArrayBlockingQueue<>(256));
    /** UID -> 当前秒的发布计数；由同步的 rate 方法保护。 */
    private final Map<Integer, Rate> rates = new HashMap<>();
    /** 单个 UID 在 elapsedRealtime 所属秒内的请求次数。 */
    private static final class Rate { long second; int count; }
    EventHub(AccessController acl) { this.acl = acl; }
    /** 客户端 Binder 死亡后释放订阅，避免僵尸监听占用 64 个名额。 */
    private final class Entry implements IBinder.DeathRecipient {
        /** 内部类日志标识。 */
        private static final String TAG = "EventHub.Entry";

        /** id 是取消句柄；uid 是创建者；address 是完整 scope/owner/topic。 */
        final long id; final int uid; final AccessController.Resource address; final IEventListener listener;
        Entry(long id, int uid, AccessController.Resource address, IEventListener listener) {
            this.id = id; this.uid = uid; this.address = address; this.listener = listener;
        }
        /** 客户端进程退出后移除失效监听。 */
        @Override public void binderDied() {
            TLog.w(TAG, "listener binder died id=" + id + " uid=" + uid);
            remove(id, uid);
        }
    }
    /** 登记 topic 订阅，并检查空监听器及每 UID 的 64 个订阅上限。 */
    long add(int uid, AccessController.Resource address, IEventListener listener) {
        TLog.d(TAG, "register uid=" + uid + " " + TLog.address(address));
        // 1. 限制空回调和单 UID 订阅数，避免在线事件占满服务端 Binder 登记。
        if (listener == null) {
            TLog.w(TAG, "register rejected null listener uid=" + uid);
            return -1;
        }
        long count = entries.values().stream().filter(e -> e.uid == uid).count();
        if (count >= 64) {
            TLog.w(TAG, "register quota uid=" + uid);
            return -1;
        }
        // 2. 先挂 Binder 死亡监听，确认客户端存活后再向投递线程发布 Entry。
        Entry entry = new Entry(next.getAndIncrement(), uid, address, listener);
        try { listener.asBinder().linkToDeath(entry, 0); }
        catch (RemoteException e) {
            TLog.w(TAG, "register binder unavailable uid=" + uid);
            return -1;
        }
        entries.put(entry.id, entry);
        TLog.d(TAG, "registered id=" + entry.id + " uid=" + uid + " " + TLog.address(address));
        return entry.id;
    }
    /** 用原登记 UID 校验取消操作，避免其他 App 猜中 ID 后移除其监听。 */
    void remove(long id, int uid) {
        TLog.d(TAG, "remove request id=" + id + " uid=" + uid);
        // 订阅 ID 不是权限凭据；必须同时匹配创建它的 UID。
        Entry entry = entries.get(id);
        if (entry == null || entry.uid != uid) return;
        entries.remove(id); entry.listener.asBinder().unlinkToDeath(entry, 0);
        TLog.d(TAG, "removed id=" + id + " uid=" + uid);
    }
    /**
     * 使用不受系统时钟校准影响的单调时间按 UID 限速，
     * 防止单个发布者持续占满队列并挤掉其他 App 的在线信号。
     */
    synchronized int rate(int uid) {
        // elapsedRealtime 不随手动调时变化，适合固定时窗的防刷限制。
        long second = android.os.SystemClock.elapsedRealtime() / 1000;
        Rate r = rates.computeIfAbsent(uid, ignored -> new Rate());
        // 进入新的一秒就清零该 UID 的计数；超额事件不会进入投递队列。
        if (r.second != second) { r.second = second; r.count = 0; }
        return ++r.count <= 20 ? Wire.OK : Wire.QUOTA_EXCEEDED;
    }
    /**
     * 将 topic 信号放入在线队列。权限可能在排队期间被撤销，所以投递时
     * 再次核对 SUBSCRIBE；队列满返回 BUSY，已入队则返回 OK，
     * 但回调失败不会回滚发布，调用方不能据此推断对端已处理事件。
     */
    int publish(int uid, AccessController.Resource address, String epoch) {
        TLog.d(TAG, "publish uid=" + uid + " " + TLog.address(address) + " epoch=" + epoch);
        // 1. 先限速再入队，避免某个发送者用事件洪峰挤占有限队列。
        int rateStatus = rate(uid); if (rateStatus != Wire.OK) {
            TLog.w(TAG, "publish rate limited uid=" + uid);
            return rateStatus;
        }
        try {
            // 2. 入队后即返回；队列线程按完整 topic 遍历当前在线订阅。
            queue.execute(() -> {
                for (Entry entry : entries.values()) {
                    if (!entry.address.id().equals(address.id())) continue;
                    // 3. 排队期间 grant 可能被撤销，投递前重新检查订阅者权限。
                    if (acl.check(entry.uid, address, Wire.SUBSCRIBE) != Wire.OK) {
                        TLog.w(TAG, "delivery revoked id=" + entry.id + " uid=" + entry.uid);
                        remove(entry.id, entry.uid); continue;
                    }
                    // 4. 只交付地址和服务 epoch，真正业务状态应由客户端另行读取。
                    Bundle event = new Bundle(); event.putInt(Wire.SCOPE, address.scope);
                    event.putString(Wire.OWNER, address.owner); event.putString(Wire.TOPIC, address.name);
                    event.putString(Wire.EPOCH, epoch);
                    try {
                        entry.listener.onEvent(event);
                        TLog.d(TAG, "delivered id=" + entry.id + " " + TLog.address(address));
                    }
                    catch (RemoteException e) {
                        // 个别接收者退出不影响同一 topic 的其他订阅者。
                        TLog.w(TAG, "delivery binder failure id=" + entry.id);
                        remove(entry.id, entry.uid);
                    }
                }
            });
            TLog.d(TAG, "publish queued uid=" + uid + " " + TLog.address(address));
            return Wire.OK;
        } catch (RejectedExecutionException full) {
            // 5. 队列满时明确返回 BUSY，不假称事件已被接收。
            TLog.w(TAG, "queue full uid=" + uid);
            return Wire.BUSY;
        }
    }
}
