package com.android.server.globaldata;

import com.oem.globaldata.ipc.Wire;
import java.util.HashMap;
import java.util.Map;

/**
 * 仅存在于 system_server 进程内的键值后端；重启后值与版本均消失。
 * 所有公开操作同步执行，以免配额计算、条件检查和提交互相穿插。
 * 适合无需跨重启保留的临时状态，避免为高频短生命周期数据产生磁盘写入。
 */
final class MemoryBackend implements Backend {
    /** 日志二级标识。 */
    private static final String TAG = "MemoryBackend";

    /** 限制单个 owner 消耗的 system_server 堆内存，防止客户端持续写入挤占系统进程。 */
    private static final long MAX_OWNER_BYTES = 4L * 1024 * 1024;
    /** namespaceId -> 记录及其版本高水位；只在 synchronized 方法中访问。 */
    private final Map<String, Namespace> namespaces = new HashMap<>();
    /**
     * 一组 Key 共享版本计数。删除后若版本归零，持有旧“不存在”快照的客户端
     * 可能在新一轮写入时误通过 CAS；因此 highWater 随成功变更继续增长。
     */
    private static final class Namespace {
        /** key -> 当前编码值。 */
        final Map<String, StoredValue> records = new HashMap<>();
        /** 最近一次成功写入或删除的版本，用于不存在值的 CAS 比较。 */
        long highWater;
    }
    /** 查找或创建地址对应的内存 namespace。 */
    private Namespace ns(StoreAddress key) {
        return namespaces.computeIfAbsent(key.namespaceId(), id -> new Namespace());
    }
    /** 返回当前值；不存在时返回 namespace 高水位而非固定 0。 */
    @Override public synchronized Snapshot read(StoreAddress key) {
        Namespace ns = ns(key); StoredValue value = ns.records.get(key.key);
        // Key 不存在也返回 namespace 高水位，供客户端基于“当前不存在”做条件写入。
        TLog.d(TAG, "read " + TLog.address(key) + " present=" + (value != null)
                + " revision=" + (value == null ? ns.highWater : value.revision));
        return new Snapshot(value, value == null ? ns.highWater : value.revision);
    }
    /**
     * 在同一个 synchronized 临界区内校验条件、计算 owner 配额并提交，
     * 防止两个并发写入各自看见有余量却合计超过 4 MiB。
     */
    @Override public synchronized Change write(StoreAddress key, StoredValue value, int mode, long expected) {
        Namespace ns = ns(key); StoredValue old = ns.records.get(key.key);
        // 1. 先用旧值或缺失高水位校验 UPSERT/IF_ABSENT/IF_REVISION。
        int status = Backend.precondition(old, ns.highWater, mode, expected);
        if (status != Wire.OK) {
            TLog.d(TAG, "write precondition " + TLog.address(key) + " status=" + status);
            return new Change(status, 0, old == null ? ns.highWater : old.revision);
        }
        // 2. used 统计当前 owner 所有 namespace 的载荷，并以新值替换旧值的字节数。
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
        // 3. 校验通过才推进高水位；版本和字节内容在同一临界区发布。
        long revision = ++ns.highWater;
        ns.records.put(key.key, value.withRevision(revision));
        TLog.d(TAG, "write committed " + TLog.address(key) + " revision=" + revision);
        return new Change(Wire.OK, old == null ? Wire.CREATE_OP : Wire.UPDATE_OP, revision);
    }
    /** 删除存在的记录并递增高水位；不存在或版本冲突时不改变状态。 */
    @Override public synchronized Change delete(StoreAddress key, long expected) {
        Namespace ns = ns(key); StoredValue old = ns.records.get(key.key);
        // 1. 不存在时保持高水位不变；返回它供调用方判断当前快照。
        if (old == null) {
            TLog.d(TAG, "delete absent " + TLog.address(key) + " revision=" + ns.highWater);
            return new Change(Wire.NOT_FOUND, 0, ns.highWater);
        }
        // 2. 有条件删除必须命中现存值版本，不能只对比 namespace 高水位。
        if (expected >= 0 && expected != old.revision) {
            TLog.d(TAG, "delete conflict " + TLog.address(key) + " revision=" + old.revision);
            return new Change(Wire.CONFLICT, 0, old.revision);
        }
        // 3. 真正移除记录后仍推进版本，防止旧的不存在快照再次通过 CAS。
        ns.records.remove(key.key);
        // 删除后仍推进 namespace 高水位，旧的“不存在”快照不能再次通过 CAS（ABA）。
        TLog.d(TAG, "delete committed " + TLog.address(key) + " revision=" + (ns.highWater + 1));
        return new Change(Wire.OK, Wire.DELETE_OP, ++ns.highWater);
    }
}
