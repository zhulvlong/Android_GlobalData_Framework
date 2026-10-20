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

/** Topic-only best-effort signals. Nothing is saved in any Backend. */
final class EventHub {
    private static final String TAG = "EventHub";

    private final AccessController acl;
    private final AtomicLong next = new AtomicLong(1);
    private final Map<Long, Entry> entries = new ConcurrentHashMap<>();
    private final ThreadPoolExecutor queue = new ThreadPoolExecutor(1, 1, 0, TimeUnit.SECONDS,
            new ArrayBlockingQueue<>(256));
    private final Map<Integer, Rate> rates = new HashMap<>();
    private static final class Rate { long second; int count; }
    EventHub(AccessController acl) { this.acl = acl; }
    private final class Entry implements IBinder.DeathRecipient {
        private static final String TAG = "EventHub.Entry";

        final long id; final int uid; final AccessController.Resource address; final IEventListener listener;
        Entry(long id, int uid, AccessController.Resource address, IEventListener listener) {
            this.id = id; this.uid = uid; this.address = address; this.listener = listener;
        }
        @Override public void binderDied() {
            TLog.w(TAG, "listener binder died id=" + id + " uid=" + uid);
            remove(id, uid);
        }
    }
    long add(int uid, AccessController.Resource address, IEventListener listener) {
        TLog.d(TAG, "register uid=" + uid + " " + TLog.address(address));
        if (listener == null) {
            TLog.w(TAG, "register rejected null listener uid=" + uid);
            return -1;
        }
        long count = entries.values().stream().filter(e -> e.uid == uid).count();
        if (count >= 64) {
            TLog.w(TAG, "register quota uid=" + uid);
            return -1;
        }
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
    void remove(long id, int uid) {
        TLog.d(TAG, "remove request id=" + id + " uid=" + uid);
        Entry entry = entries.get(id);
        if (entry == null || entry.uid != uid) return;
        entries.remove(id); entry.listener.asBinder().unlinkToDeath(entry, 0);
        TLog.d(TAG, "removed id=" + id + " uid=" + uid);
    }
    synchronized int rate(int uid) {
        long second = android.os.SystemClock.elapsedRealtime() / 1000;
        Rate r = rates.computeIfAbsent(uid, ignored -> new Rate());
        if (r.second != second) { r.second = second; r.count = 0; }
        return ++r.count <= 20 ? Wire.OK : Wire.QUOTA_EXCEEDED;
    }
    int publish(int uid, AccessController.Resource address, String epoch) {
        TLog.d(TAG, "publish uid=" + uid + " " + TLog.address(address) + " epoch=" + epoch);
        int rateStatus = rate(uid); if (rateStatus != Wire.OK) {
            TLog.w(TAG, "publish rate limited uid=" + uid);
            return rateStatus;
        }
        try {
            queue.execute(() -> {
                for (Entry entry : entries.values()) {
                    if (!entry.address.id().equals(address.id())) continue;
                    if (acl.check(entry.uid, address, Wire.SUBSCRIBE) != Wire.OK) {
                        TLog.w(TAG, "delivery revoked id=" + entry.id + " uid=" + entry.uid);
                        remove(entry.id, entry.uid); continue;
                    }
                    Bundle event = new Bundle(); event.putInt(Wire.SCOPE, address.scope);
                    event.putString(Wire.OWNER, address.owner); event.putString(Wire.TOPIC, address.name);
                    event.putString(Wire.EPOCH, epoch);
                    try {
                        entry.listener.onEvent(event);
                        TLog.d(TAG, "delivered id=" + entry.id + " " + TLog.address(address));
                    }
                    catch (RemoteException e) {
                        TLog.w(TAG, "delivery binder failure id=" + entry.id);
                        remove(entry.id, entry.uid);
                    }
                }
            });
            TLog.d(TAG, "publish queued uid=" + uid + " " + TLog.address(address));
            return Wire.OK;
        } catch (RejectedExecutionException full) {
            TLog.w(TAG, "queue full uid=" + uid);
            return Wire.BUSY;
        }
    }
}
