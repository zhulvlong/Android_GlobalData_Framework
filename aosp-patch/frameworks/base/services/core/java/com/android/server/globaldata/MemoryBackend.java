package com.android.server.globaldata;

import com.oem.globaldata.ipc.Wire;
import java.util.HashMap;
import java.util.Map;

/** Authoritative bytes live only for the lifetime of system_server. */
final class MemoryBackend implements Backend {
    private static final String TAG = "MemoryBackend";

    private static final long MAX_OWNER_BYTES = 4L * 1024 * 1024;
    private final Map<String, Namespace> namespaces = new HashMap<>();
    private static final class Namespace {
        final Map<String, StoredValue> records = new HashMap<>();
        long highWater;
    }
    private Namespace ns(StoreAddress key) {
        return namespaces.computeIfAbsent(key.namespaceId(), id -> new Namespace());
    }
    @Override public synchronized Snapshot read(StoreAddress key) {
        Namespace ns = ns(key); StoredValue value = ns.records.get(key.key);
        TLog.d(TAG, "read " + TLog.address(key) + " present=" + (value != null)
                + " revision=" + (value == null ? ns.highWater : value.revision));
        return new Snapshot(value, value == null ? ns.highWater : value.revision);
    }
    @Override public synchronized Change write(StoreAddress key, StoredValue value, int mode, long expected) {
        Namespace ns = ns(key); StoredValue old = ns.records.get(key.key);
        int status = Backend.precondition(old, ns.highWater, mode, expected);
        if (status != Wire.OK) {
            TLog.d(TAG, "write precondition " + TLog.address(key) + " status=" + status);
            return new Change(status, 0, old == null ? ns.highWater : old.revision);
        }
        long used = 0;
        for (Map.Entry<String, Namespace> n : namespaces.entrySet()) {
            if (n.getKey().contains("|" + key.owner + "|"))
                for (StoredValue r : n.getValue().records.values()) used += r.payload.length;
        }
        used += value.payload.length - (old == null ? 0 : old.payload.length);
        if (used > MAX_OWNER_BYTES) {
            TLog.d(TAG, "write quota " + TLog.address(key) + " bytes=" + used);
            return new Change(Wire.QUOTA_EXCEEDED, 0, ns.highWater);
        }
        long revision = ++ns.highWater;
        ns.records.put(key.key, value.withRevision(revision));
        TLog.d(TAG, "write committed " + TLog.address(key) + " revision=" + revision);
        return new Change(Wire.OK, old == null ? Wire.CREATE_OP : Wire.UPDATE_OP, revision);
    }
    @Override public synchronized Change delete(StoreAddress key, long expected) {
        Namespace ns = ns(key); StoredValue old = ns.records.get(key.key);
        if (old == null) {
            TLog.d(TAG, "delete absent " + TLog.address(key) + " revision=" + ns.highWater);
            return new Change(Wire.NOT_FOUND, 0, ns.highWater);
        }
        if (expected >= 0 && expected != old.revision) {
            TLog.d(TAG, "delete conflict " + TLog.address(key) + " revision=" + old.revision);
            return new Change(Wire.CONFLICT, 0, old.revision);
        }
        ns.records.remove(key.key);
        // Namespace high-water survives deletion, so an old absent CAS cannot pass (ABA).
        TLog.d(TAG, "delete committed " + TLog.address(key) + " revision=" + (ns.highWater + 1));
        return new Change(Wire.OK, Wire.DELETE_OP, ++ns.highWater);
    }
}
