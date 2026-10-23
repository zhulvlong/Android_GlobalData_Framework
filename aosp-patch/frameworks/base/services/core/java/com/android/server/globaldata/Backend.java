package com.android.server.globaldata;

import com.oem.globaldata.ipc.Wire;

/**
 * 三种存储后端的统一契约。系统服务完成地址解析和授权后调用这里，
 * 后端负责单次读写的版本、CAS 和实际存储提交。
 * 将条件检查统一在此契约，是为了让内存、文件和数据库对同一个 SDK 调用
 * 返回一致的冲突/版本语义，避免换后端后业务代码必须重写并发控制。
 */
interface Backend {
    /** 读取当前值；不存在时 Snapshot.revision 是 namespace 版本高水位。 */
    Snapshot read(StoreAddress key);
    /** 按 mode 和 expected 执行写入，返回状态、变更类型及最新版本。 */
    Change write(StoreAddress key, StoredValue value, int mode, long expected);
    /** expected 为负数时无条件删除，否则须匹配当前值版本。 */
    Change delete(StoreAddress key, long expected);

    /**
     * 读结果即使不存在也携带 revision：客户端可以基于“当前确实不存在”的快照
     * 发起条件写入，而不是把不存在误认为永远等于版本 0。
     */
    final class Snapshot {
        /** 当前编码值，或 null。 */
        final StoredValue value;
        /** 值版本；值不存在时为 namespace 的 highWater。 */
        final long revision;
        Snapshot(StoredValue value, long revision) { this.value = value; this.revision = revision; }
    }
    /** 写入或删除的结果；operation 仅在成功提交时为 CREATE/UPDATE/DELETE。 */
    final class Change {
        /** status 为 Wire 状态码；operation 为变更类型，未变更时为 0。 */
        final int status, operation;
        /** 成功后的版本，或失败时供调用方观察的现有版本。 */
        final long revision;
        Change(int status, int operation, long revision) {
            this.status = status; this.operation = operation; this.revision = revision;
        }
    }
    /**
     * 三个后端共用的写入前置条件。不存在时用 namespace 高水位比较 revision，
     * 防止删除后旧的“不存在”快照在同一 namespace 内通过 CAS（ABA）；
     * 同一逻辑集中实现也能减少各后端对 UPSERT/IF_ABSENT/IF_REVISION 的分歧。
     */
    static int precondition(StoredValue old, long absentRevision, int mode, long expected) {
        // 1. UPSERT 不使用快照；IF_ABSENT 只看该 Key 是否已有记录。
        if (mode == Wire.UPSERT) return Wire.OK;
        if (mode == Wire.IF_ABSENT) return old == null ? Wire.OK : Wire.CONFLICT;
        // 2. CAS：有值比对值版本；无值比对 namespace 高水位以识别删除后的变化。
        if (mode == Wire.IF_REVISION)
            return expected == (old == null ? absentRevision : old.revision) ? Wire.OK : Wire.CONFLICT;
        // 3. 未知模式不交给后端猜测执行，直接作为错误请求返回。
        return Wire.BAD_REQUEST;
    }
}
