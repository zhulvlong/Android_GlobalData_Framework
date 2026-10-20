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
    private final AtomicLong next = new AtomicLong(1);
    private final Map<Long, Entry> entries = new ConcurrentHashMap<>();
    private final ThreadPoolExecutor queue = new ThreadPoolExecutor(1, 1, 0, TimeUnit.SECONDS,
            new ArrayBlockingQueue<>(256));
    private final AccessController acl;
    DataObservers(AccessController acl) { this.acl = acl; }
    private final class Entry implements IBinder.DeathRecipient {
        final long id; final int uid; final StoreAddress key; final IDataObserver observer;
        Entry(long id, int uid, StoreAddress key, IDataObserver observer) {
            this.id = id; this.uid = uid; this.key = key; this.observer = observer;
        }
        @Override public void binderDied() { remove(id, uid); }
    }
    long add(int uid, StoreAddress key, IDataObserver observer) {
        if (observer == null) return -1;
        long count = entries.values().stream().filter(e -> e.uid == uid).count();
        if (count >= 64) return -1;
        Entry entry = new Entry(next.getAndIncrement(), uid, key, observer);
        try { observer.asBinder().linkToDeath(entry, 0); }
        catch (RemoteException e) { return -1; }
        entries.put(entry.id, entry); return entry.id;
    }
    void remove(long id, int uid) {
        Entry entry = entries.get(id);
        if (entry == null || entry.uid != uid) return;
        entries.remove(id); entry.observer.asBinder().unlinkToDeath(entry, 0);
    }
    void changed(StoreAddress key, int operation, long revision, String epoch) {
        for (Entry entry : entries.values()) {
            if (!entry.key.equals(key)) continue;
            try {
                queue.execute(() -> {
                    if (acl.check(entry.uid, AccessController.data(key), Wire.OBSERVE) != Wire.OK) {
                        remove(entry.id, entry.uid); return;
                    }
                    Bundle event = new Bundle(); event.putInt(Wire.STORE, key.store);
                    event.putInt(Wire.SCOPE, key.scope); event.putString(Wire.OWNER, key.owner);
                    event.putString(Wire.NAMESPACE, key.namespace); event.putString(Wire.KEY, key.key);
                    event.putInt(Wire.OPERATION, operation); event.putLong(Wire.REVISION, revision);
                    event.putString(Wire.EPOCH, epoch);
                    try { entry.observer.onDataChanged(event); }
                    catch (RemoteException e) { remove(entry.id, entry.uid); }
                });
            } catch (RejectedExecutionException overflow) {
                try { entry.observer.onResyncRequired(epoch); }
                catch (RemoteException e) { remove(entry.id, entry.uid); }
            }
        }
    }
}
