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
    private final AccessController acl;
    private final AtomicLong next = new AtomicLong(1);
    private final Map<Long, Entry> entries = new ConcurrentHashMap<>();
    private final ThreadPoolExecutor queue = new ThreadPoolExecutor(1, 1, 0, TimeUnit.SECONDS,
            new ArrayBlockingQueue<>(256));
    private final Map<Integer, Rate> rates = new HashMap<>();
    private static final class Rate { long second; int count; }
    EventHub(AccessController acl) { this.acl = acl; }
    private final class Entry implements IBinder.DeathRecipient {
        final long id; final int uid; final AccessController.Resource address; final IEventListener listener;
        Entry(long id, int uid, AccessController.Resource address, IEventListener listener) {
            this.id = id; this.uid = uid; this.address = address; this.listener = listener;
        }
        @Override public void binderDied() { remove(id, uid); }
    }
    long add(int uid, AccessController.Resource address, IEventListener listener) {
        if (listener == null) return -1;
        long count = entries.values().stream().filter(e -> e.uid == uid).count();
        if (count >= 64) return -1;
        Entry entry = new Entry(next.getAndIncrement(), uid, address, listener);
        try { listener.asBinder().linkToDeath(entry, 0); }
        catch (RemoteException e) { return -1; }
        entries.put(entry.id, entry); return entry.id;
    }
    void remove(long id, int uid) {
        Entry entry = entries.get(id);
        if (entry == null || entry.uid != uid) return;
        entries.remove(id); entry.listener.asBinder().unlinkToDeath(entry, 0);
    }
    synchronized int rate(int uid) {
        long second = android.os.SystemClock.elapsedRealtime() / 1000;
        Rate r = rates.computeIfAbsent(uid, ignored -> new Rate());
        if (r.second != second) { r.second = second; r.count = 0; }
        return ++r.count <= 20 ? Wire.OK : Wire.QUOTA_EXCEEDED;
    }
    int publish(int uid, AccessController.Resource address, String epoch) {
        int rateStatus = rate(uid); if (rateStatus != Wire.OK) return rateStatus;
        try {
            queue.execute(() -> {
                for (Entry entry : entries.values()) {
                    if (!entry.address.id().equals(address.id())) continue;
                    if (acl.check(entry.uid, address, Wire.SUBSCRIBE) != Wire.OK) {
                        remove(entry.id, entry.uid); continue;
                    }
                    Bundle event = new Bundle(); event.putInt(Wire.SCOPE, address.scope);
                    event.putString(Wire.OWNER, address.owner); event.putString(Wire.TOPIC, address.name);
                    event.putString(Wire.EPOCH, epoch);
                    try { entry.listener.onEvent(event); }
                    catch (RemoteException e) { remove(entry.id, entry.uid); }
                }
            });
            return Wire.OK;
        } catch (RejectedExecutionException full) { return Wire.BUSY; }
    }
}
