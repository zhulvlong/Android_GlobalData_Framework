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

/** Sends invalidations after commits, outside all storage locks. */
final class DataObservers {
    private static final String TAG = "DataObservers";

    private final AtomicLong next = new AtomicLong(1);
    private final Map<Long, Entry> entries = new ConcurrentHashMap<>();
    private final ThreadPoolExecutor queue = new ThreadPoolExecutor(1, 1, 0, TimeUnit.SECONDS,
            new ArrayBlockingQueue<>(256));
    private final AccessController acl;
    DataObservers(AccessController acl) { this.acl = acl; }
    private final class Entry implements IBinder.DeathRecipient {
        private static final String TAG = "DataObservers.Entry";

        final long id; final int uid; final StoreAddress key; final IDataObserver observer;
        Entry(long id, int uid, StoreAddress key, IDataObserver observer) {
            this.id = id; this.uid = uid; this.key = key; this.observer = observer;
        }
        @Override public void binderDied() {
            TLog.w(TAG, "listener binder died id=" + id + " uid=" + uid);
            remove(id, uid);
        }
    }
    long add(int uid, StoreAddress key, IDataObserver observer) {
        TLog.d(TAG, "register uid=" + uid + " " + TLog.address(key));
        if (observer == null) {
            TLog.w(TAG, "register rejected null listener uid=" + uid);
            return -1;
        }
        long count = entries.values().stream().filter(e -> e.uid == uid).count();
        if (count >= 64) {
            TLog.w(TAG, "register quota uid=" + uid);
            return -1;
        }
        Entry entry = new Entry(next.getAndIncrement(), uid, key, observer);
        try { observer.asBinder().linkToDeath(entry, 0); }
        catch (RemoteException e) {
            TLog.w(TAG, "register binder unavailable uid=" + uid);
            return -1;
        }
        entries.put(entry.id, entry);
        TLog.d(TAG, "registered id=" + entry.id + " uid=" + uid + " " + TLog.address(key));
        return entry.id;
    }
    void remove(long id, int uid) {
        TLog.d(TAG, "remove request id=" + id + " uid=" + uid);
        Entry entry = entries.get(id);
        if (entry == null || entry.uid != uid) return;
        entries.remove(id); entry.observer.asBinder().unlinkToDeath(entry, 0);
        TLog.d(TAG, "removed id=" + id + " uid=" + uid);
    }
    void changed(StoreAddress key, int operation, long revision, String epoch) {
        TLog.d(TAG, "change " + TLog.address(key) + " operation=" + operation
                + " revision=" + revision + " epoch=" + epoch);
        for (Entry entry : entries.values()) {
            if (!entry.key.equals(key)) continue;
            try {
                queue.execute(() -> {
                    if (acl.check(entry.uid, AccessController.data(key), Wire.OBSERVE) != Wire.OK) {
                        TLog.w(TAG, "delivery revoked id=" + entry.id + " uid=" + entry.uid);
                        remove(entry.id, entry.uid); return;
                    }
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
                        TLog.w(TAG, "delivery binder failure id=" + entry.id);
                        remove(entry.id, entry.uid);
                    }
                });
            } catch (RejectedExecutionException overflow) {
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
