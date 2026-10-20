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

/** Publishes a small, versioned Binder transport from system_server. */
public final class GlobalDataSystemService extends SystemService {
    private final String epoch = UUID.randomUUID().toString();
    private final ThreadPoolExecutor storageIo = new ThreadPoolExecutor(1, 1, 0,
            TimeUnit.SECONDS, new ArrayBlockingQueue<>(128));
    private final MemoryBackend memory = new MemoryBackend();
    private final FileBackend file = new FileBackend();
    private final DatabaseBackend database = new DatabaseBackend();
    private AccessController acl;
    private DataObservers observers;
    private EventHub events;

    public GlobalDataSystemService(Context context) { super(context); }
    @Override public void onStart() {
        acl = new AccessController(getContext());
        observers = new DataObservers(acl); events = new EventHub(acl);
        publishBinderService("globaldata", new Transport());
    }
    private Backend backend(int store) {
        switch (store) {
            case Wire.MEMORY: return memory;
            case Wire.FILE: return file;
            case Wire.DATABASE: return database;
            default: throw new IllegalArgumentException("store");
        }
    }
    private Bundle status(int code) {
        Bundle b = new Bundle(); b.putInt(Wire.STATUS, code); b.putString(Wire.EPOCH, epoch); return b;
    }
    private Bundle failure(Exception e) {
        if (e instanceof IllegalArgumentException) return status(Wire.BAD_REQUEST);
        return status(Wire.STORAGE_UNAVAILABLE);
    }
    private Bundle io(Callable<Bundle> work) {
        Future<Bundle> task;
        try {
            task = storageIo.submit(work);
        } catch (RejectedExecutionException full) { return status(Wire.BUSY); }
        boolean interrupted = false;
        try {
            // Wait through Binder-thread interruption so no commit is reported as a failure.
            while (true) {
                try { return task.get(); }
                catch (InterruptedException e) { interrupted = true; }
            }
        } catch (Exception e) { return failure(e); }
        finally { if (interrupted) Thread.currentThread().interrupt(); }
    }
    private final class Transport extends IGlobalDataTransport.Stub {
        @Override public Bundle getCapabilities() {
            Bundle b = status(Wire.OK); b.putInt(Wire.MAJOR, Wire.PROTOCOL_MAJOR);
            b.putInt(Wire.MINOR, Wire.PROTOCOL_MINOR); b.putInt(Wire.MAX_BYTES, Wire.MAX_PAYLOAD);
            b.putLong(Wire.FEATURES, Wire.ALL_FEATURES); return b;
        }
        @Override public Bundle read(Bundle request) {
            int uid = Binder.getCallingUid(), user = UserHandle.getUserId(uid);
            try {
                StoreAddress key = StoreAddress.parse(request, user);
                int access = acl.authorize(uid, AccessController.data(key), Wire.READ, false);
                if (access != Wire.OK) return status(access);
                return io(() -> {
                    Backend.Snapshot snapshot = backend(key.store).read(key);
                    Bundle b = status(snapshot.value == null ? Wire.NOT_FOUND : Wire.OK);
                    b.putBoolean(Wire.PRESENT, snapshot.value != null);
                    b.putLong(Wire.REVISION, snapshot.revision);
                    if (snapshot.value != null) b.putBundle(Wire.VALUE, snapshot.value.wire());
                    return b;
                });
            } catch (Exception e) { return failure(e); }
        }
        @Override public Bundle write(Bundle request, Bundle encoded, int mode, long expectedRevision) {
            int uid = Binder.getCallingUid(), user = UserHandle.getUserId(uid);
            try {
                StoreAddress key = StoreAddress.parse(request, user);
                StoredValue value = StoredValue.parse(encoded);
                int access = acl.authorize(uid, AccessController.data(key), Wire.WRITE, true);
                if (access != Wire.OK) return status(access);
                if (mode == Wire.IF_REVISION
                        && !epoch.equals(request.getString(Wire.EXPECTED_EPOCH)))
                    return status(Wire.CONFLICT);
                return io(() -> {
                    Backend.Change change = backend(key.store).write(key, value, mode, expectedRevision);
                    Bundle b = status(change.status); b.putLong(Wire.REVISION, change.revision);
                    if (change.status == Wire.OK)
                        observers.changed(key, change.operation, change.revision, epoch);
                    return b;
                });
            } catch (Exception e) { return failure(e); }
        }
        @Override public Bundle delete(Bundle request, long expectedRevision) {
            int uid = Binder.getCallingUid(), user = UserHandle.getUserId(uid);
            try {
                StoreAddress key = StoreAddress.parse(request, user);
                int access = acl.authorize(uid, AccessController.data(key), Wire.DELETE, false);
                if (access != Wire.OK) return status(access);
                if (expectedRevision >= 0
                        && !epoch.equals(request.getString(Wire.EXPECTED_EPOCH)))
                    return status(Wire.CONFLICT);
                return io(() -> {
                    Backend.Change change = backend(key.store).delete(key, expectedRevision);
                    Bundle b = status(change.status); b.putLong(Wire.REVISION, change.revision);
                    if (change.status == Wire.OK)
                        observers.changed(key, change.operation, change.revision, epoch);
                    return b;
                });
            } catch (Exception e) { return failure(e); }
        }
        @Override public Bundle grant(Bundle resource, String targetPackage, int rights) {
            int uid = Binder.getCallingUid(), user = UserHandle.getUserId(uid);
            try {
                AccessController.Resource r = AccessController.grantResource(resource, user);
                return io(() -> status(acl.changeGrant(uid, r, targetPackage, rights, true)));
            } catch (Exception e) { return failure(e); }
        }
        @Override public Bundle revoke(Bundle resource, String targetPackage, int rights) {
            int uid = Binder.getCallingUid(), user = UserHandle.getUserId(uid);
            try {
                AccessController.Resource r = AccessController.grantResource(resource, user);
                return io(() -> status(acl.changeGrant(uid, r, targetPackage, rights, false)));
            } catch (Exception e) { return failure(e); }
        }
        @Override public long subscribeData(Bundle request, IDataObserver observer) {
            int uid = Binder.getCallingUid(), user = UserHandle.getUserId(uid);
            try {
                StoreAddress key = StoreAddress.parse(request, user);
                if (acl.authorize(uid, AccessController.data(key), Wire.OBSERVE, false) != Wire.OK) return -1;
                return observers.add(uid, key, observer);
            } catch (Exception e) { return -1; }
        }
        @Override public void unsubscribeData(long id) { observers.remove(id, Binder.getCallingUid()); }
        @Override public Bundle publishEvent(Bundle address) {
            int uid = Binder.getCallingUid(), user = UserHandle.getUserId(uid);
            try {
                AccessController.Resource r = AccessController.event(address, user);
                int access = acl.authorize(uid, r, Wire.PUBLISH, true);
                return status(access == Wire.OK ? events.publish(uid, r, epoch) : access);
            } catch (Exception e) { return failure(e); }
        }
        @Override public long subscribeEvent(Bundle address, IEventListener observer) {
            int uid = Binder.getCallingUid(), user = UserHandle.getUserId(uid);
            try {
                AccessController.Resource r = AccessController.event(address, user);
                if (acl.authorize(uid, r, Wire.SUBSCRIBE, false) != Wire.OK) return -1;
                return events.add(uid, r, observer);
            } catch (Exception e) { return -1; }
        }
        @Override public void unsubscribeEvent(long id) { events.remove(id, Binder.getCallingUid()); }
    }
}
