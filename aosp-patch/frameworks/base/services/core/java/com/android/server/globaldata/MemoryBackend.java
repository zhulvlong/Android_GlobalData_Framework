package com.android.server.globaldata;

import com.oem.globaldata.ipc.Wire;
import java.util.HashMap;
import java.util.Map;

/** 进程内存储，配额与高水位随 system_server 生命周期。 */
final class MemoryBackend implements Backend {
    // 配额与存储地址使用相同的作用域边界，避免同一包的不同用户互占名额。
    private record OwnerGroup(int scope, int userId, String owner) {
        OwnerGroup(StoreAddress key) {
            this(key.scope, key.userId, key.owner);
        }
    }

    private static final class Group {
        final Map<String, Namespace> namespaces = new HashMap<>();
        int records;
        long payloadBytes;
        long metadataBytes;
    }

    private static final class Namespace {
        final Map<String, StoredValue> records = new HashMap<>();
        long highWater;
    }

    private final Map<OwnerGroup, Group> groups = new HashMap<>();

    private static Namespace find(Group group, StoreAddress key) {
        return group == null ? null : group.namespaces.get(key.namespace);
    }

    private static long metadata(StoreAddress key, StoredValue value) {
        return StorageQuota.recordBytes(key.owner, key.namespace, key.key, value);
    }

    @Override public synchronized Snapshot read(StoreAddress key) {
        // 缺失读取不能消耗用于保留高水位的 namespace 名额。
        Namespace ns = find(groups.get(new OwnerGroup(key)), key);
        if (ns == null) return new Snapshot(null, 0);
        StoredValue value = ns.records.get(key.key);
        return new Snapshot(value, value == null ? ns.highWater : value.revision);
    }

    @Override public synchronized Change write(StoreAddress key, StoredValue value, int mode,
            long expected) {
        OwnerGroup owner = new OwnerGroup(key);
        Group group = groups.get(owner);
        Namespace ns = find(group, key);
        long highWater = ns == null ? 0 : ns.highWater;
        StoredValue old = ns == null ? null : ns.records.get(key.key);
        long revision = old == null ? highWater : old.revision;
        int status = Backend.precondition(old, highWater, mode, expected);
        if (status != Wire.OK) return new Change(status, 0, revision);

        int records = (group == null ? 0 : group.records) + (old == null ? 1 : 0);
        int namespaces = (group == null ? 0 : group.namespaces.size()) + (ns == null ? 1 : 0);
        long payload = (group == null ? 0 : group.payloadBytes) + value.payload.length;
        long metadata = (group == null ? 0 : group.metadataBytes) + metadata(key, value);
        // 覆盖已有值按净增量计费，达到条数上限仍能更新。
        if (old != null) {
            payload -= old.payload.length;
            metadata -= metadata(key, old);
        }
        if (ns == null) metadata += StorageQuota.namespaceBytes(key.owner, key.namespace);
        if (records > StorageQuota.MEMORY_RECORDS || namespaces > StorageQuota.RETAINED_NAMESPACES
                || payload > StorageQuota.MEMORY_PAYLOAD || metadata > StorageQuota.MEMORY_METADATA) {
            return new Change(Wire.QUOTA_EXCEEDED, 0, revision);
        }

        long next = Math.incrementExact(highWater);
        if (group == null) {
            group = new Group();
            groups.put(owner, group);
        }
        if (ns == null) {
            ns = new Namespace();
            group.namespaces.put(key.namespace, ns);
        }
        ns.records.put(key.key, value.withRevision(next));
        ns.highWater = next;
        group.records = records;
        group.payloadBytes = payload;
        group.metadataBytes = metadata;
        return new Change(Wire.OK, old == null ? Wire.CREATE_OP : Wire.UPDATE_OP, next);
    }

    @Override public synchronized Change delete(StoreAddress key, long expected) {
        Group group = groups.get(new OwnerGroup(key));
        Namespace ns = find(group, key);
        if (ns == null) return new Change(Wire.NOT_FOUND, 0, 0);
        StoredValue old = ns.records.get(key.key);
        if (old == null) return new Change(Wire.NOT_FOUND, 0, ns.highWater);
        if (expected >= 0 && expected != old.revision) {
            return new Change(Wire.CONFLICT, 0, old.revision);
        }

        long next = Math.incrementExact(ns.highWater);
        ns.records.remove(key.key);
        group.records--;
        group.payloadBytes -= old.payload.length;
        group.metadataBytes -= metadata(key, old);
        // 删除释放记录配额，但保留高水位，避免旧的缺失快照再次通过 CAS。
        ns.highWater = next;
        return new Change(Wire.OK, Wire.DELETE_OP, next);
    }
}
