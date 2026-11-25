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
        long payloadBytes;
        long metadataBytes;
        final Map<String, StoredValue> records = new HashMap<>();
        long highWater;
    }

    private final Map<OwnerGroup, Group> groups = new HashMap<>();

    private static long metadata(StoreAddress key, StoredValue value) {
        return StorageQuota.recordBytes(key.owner, key.key, value);
    }

    @Override public synchronized Snapshot read(StoreAddress key) {
        // 仅在首次写入成功时登记 owner，缺失读取不会创建状态。
        Group group = groups.get(new OwnerGroup(key));
        if (group == null) return new Snapshot(null, 0);
        StoredValue value = group.records.get(key.key);
        return new Snapshot(value, value == null ? group.highWater : value.revision);
    }

    @Override public synchronized Change write(StoreAddress key, StoredValue value, int mode,
            long expected) {
        OwnerGroup owner = new OwnerGroup(key);
        Group group = groups.get(owner);
        long highWater = group == null ? 0 : group.highWater;
        StoredValue old = group == null ? null : group.records.get(key.key);
        long revision = old == null ? highWater : old.revision;
        int status = Backend.precondition(old, highWater, mode, expected);
        if (status != Wire.OK) return new Change(status, 0, revision);

        int records = (group == null ? 0 : group.records.size()) + (old == null ? 1 : 0);
        long payload = (group == null ? 0 : group.payloadBytes) + value.payload.length;
        long metadata = (group == null ? 0 : group.metadataBytes) + metadata(key, value);
        // 覆盖已有值按净增量计费，达到条数上限仍能更新。
        if (old != null) {
            payload -= old.payload.length;
            metadata -= metadata(key, old);
        }
        if (group == null) metadata += StorageQuota.ownerBytes(key.owner);
        if (records > StorageQuota.MEMORY_RECORDS
                || payload > StorageQuota.MEMORY_PAYLOAD || metadata > StorageQuota.MEMORY_METADATA) {
            return new Change(Wire.QUOTA_EXCEEDED, 0, revision);
        }

        long next = Math.incrementExact(highWater);
        if (group == null) {
            group = new Group();
            groups.put(owner, group);
        }
        group.records.put(key.key, value.withRevision(next));
        group.highWater = next;
        group.payloadBytes = payload;
        group.metadataBytes = metadata;
        return new Change(Wire.OK, old == null ? Wire.CREATE_OP : Wire.UPDATE_OP, next);
    }

    @Override public synchronized Change delete(StoreAddress key, long expected) {
        Group group = groups.get(new OwnerGroup(key));
        if (group == null) return new Change(Wire.NOT_FOUND, 0, 0);
        StoredValue old = group.records.get(key.key);
        if (old == null) return new Change(Wire.NOT_FOUND, 0, group.highWater);
        if (expected >= 0 && expected != old.revision) {
            return new Change(Wire.CONFLICT, 0, old.revision);
        }

        long next = Math.incrementExact(group.highWater);
        group.records.remove(key.key);
        group.payloadBytes -= old.payload.length;
        group.metadataBytes -= metadata(key, old);
        // 删除释放记录配额，但保留高水位，避免旧的缺失快照再次通过 CAS。
        group.highWater = next;
        return new Change(Wire.OK, Wire.DELETE_OP, next);
    }
}
