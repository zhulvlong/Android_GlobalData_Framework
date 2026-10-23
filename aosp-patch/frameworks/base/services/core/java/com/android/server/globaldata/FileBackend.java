package com.android.server.globaldata;

import android.os.Environment;
import android.os.SELinux;
import android.util.AtomicFile;
import com.oem.globaldata.ipc.Wire;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.security.MessageDigest;
import java.util.HashMap;
import java.util.Map;
import java.util.zip.CRC32;

/**
 * 文件后端：每个 scope/user/owner/namespace 使用一个有界 AtomicFile 快照。
 * 启动后按需加载并缓存；写入先构造新快照，文件原子提交成功后才替换内存缓存。
 * 按 namespace 分文件可限制单次重写和损坏影响范围；保留高水位使重启后的 CAS
 * 仍可识别删除过的记录，而不会把旧快照当作新空 namespace。
 */
final class FileBackend implements Backend {
    /** 日志二级标识。 */
    private static final String TAG = "FileBackend";

    /**
     * 魔数/版本防止误把其他格式当作当前快照；1 MiB 上限约束加载时内存
     * 和整文件重写成本，避免异常客户端让 system_server 处理无界文件。
     */
    private static final int MAGIC = 0x47444631, VERSION = 1, MAX_FILE = 1024 * 1024;
    /** namespaceId -> 最近成功加载或提交的快照；由 synchronized 入口串行访问。 */
    private final Map<String, Namespace> cache = new HashMap<>();
    /** records 保存当前值，highWater 保存成功变更的最大版本。 */
    private static final class Namespace {
        final Map<String, StoredValue> records = new HashMap<>();
        long highWater;
    }
    /** 使用 Android 系统规定的 DE/CE 目录，才能继承正确的用户隔离与解锁行为。 */
    private static File directory(StoreAddress key) {
        File parent = key.scope == Wire.USER_CE
                ? Environment.getDataSystemCeDirectory(key.userId)
                : Environment.getDataSystemDeDirectory(key.userId);
        return new File(parent, "globaldata/file");
    }
    /**
     * 用 namespaceId 的稳定 SHA-256 摘要命名文件：不同地址映射到不同快照，
     * 同时避免业务字符串中的分隔符直接进入文件路径。
     */
    private static String hash(String identity) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(
                    identity.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            StringBuilder out = new StringBuilder();
            for (byte b : digest) out.append(String.format(java.util.Locale.ROOT, "%02x", b & 0xff));
            return out.toString();
        } catch (Exception e) { throw new IllegalStateException(e); }
    }
    /**
     * 新建目录后立即恢复 SELinux 标签，否则即使 Unix 权限正确，
     * system_server 后续也可能因策略拒绝而无法打开文件。
     */
    private static AtomicFile file(StoreAddress key) {
        File dir = directory(key);
        if (!dir.exists()) {
            if (!dir.mkdirs() || !SELinux.restoreconRecursive(dir))
                throw new IllegalStateException("cannot create file store");
        }
        return new AtomicFile(new File(dir, hash(key.namespaceId()) + ".gdf"));
    }
    /**
     * 首次访问时从 AtomicFile 恢复快照：校验大小、格式、记录数及 CRC。
     * 损坏时抛出存储异常，不把空快照写入缓存；否则下一次写入可能覆盖
     * 本来还能诊断或恢复的旧数据。CRC 用于发现偶发损坏，不承担防篡改认证。
     */
    private Namespace ns(StoreAddress key) {
        // 1. 已验证过的快照直接从缓存读取，避免每次访问重复反序列化整个文件。
        Namespace found = cache.get(key.namespaceId());
        if (found != null) {
            TLog.d(TAG, "cache hit " + TLog.address(key));
            return found;
        }
        TLog.d(TAG, "load namespace " + TLog.address(key));
        AtomicFile file = file(key); Namespace loaded = new Namespace();
        // 2. 新 namespace 没有文件时保持空快照；已有文件或备份则必须完整校验。
        if (file.getBaseFile().exists()
                || new File(file.getBaseFile().getPath() + ".bak").exists()) {
            try (FileInputStream in = file.openRead()) {
                // 先限制输入总量，避免损坏文件导致无界内存读取。
                ByteArrayOutputStream out = new ByteArrayOutputStream();
                byte[] block = new byte[8192]; int count;
                while ((count = in.read(block)) != -1) {
                    out.write(block, 0, count);
                    if (out.size() > MAX_FILE) throw new IOException("file too large");
                }
                byte[] bytes = out.toByteArray();
                if (bytes.length < 20) throw new IOException("truncated file");
                DataInputStream input = new DataInputStream(new ByteArrayInputStream(bytes));
                // 3. 先确认格式版本，再按固定顺序恢复高水位和所有记录。
                if (input.readInt() != MAGIC || input.readInt() != VERSION)
                    throw new IOException("unknown file version");
                // highWater 与各记录 revision 一同恢复，删除后的高水位也不能丢失。
                loaded.highWater = input.readLong();
                int n = input.readInt();
                if (n < 0 || n > 8192) throw new IOException("invalid count");
                for (int i = 0; i < n; i++) {
                    String k = input.readUTF(), schema = input.readUTF();
                    int schemaVersion = input.readInt(); String encoding = input.readUTF();
                    long revision = input.readLong(); int size = input.readInt();
                    if (size < 0 || size > Wire.MAX_PAYLOAD) throw new IOException("invalid payload");
                    byte[] payload = new byte[size]; input.readFully(payload);
                    loaded.records.put(k, new StoredValue(schema, schemaVersion, encoding, payload, revision));
                }
                // 4. 最后 8 字节保存 CRC32；多余或截断数据都视为文件损坏。
                int crcStart = bytes.length - 8;
                if (input.available() != 8) throw new IOException("trailing data");
                long saved = input.readLong(); CRC32 crc = new CRC32(); crc.update(bytes, 0, crcStart);
                if (saved != crc.getValue()) throw new IOException("checksum mismatch");
            } catch (IOException e) {
                TLog.e(TAG, "load failed type=" + e.getClass().getSimpleName());
                throw new IllegalStateException("file store unavailable", e);
            }
        }
        TLog.d(TAG, "loaded namespace " + TLog.address(key)
                + " records=" + loaded.records.size() + " revision=" + loaded.highWater);
        // 5. 只有完整校验成功后才缓存；损坏文件不能被当作空 namespace。
        cache.put(key.namespaceId(), loaded); return loaded;
    }
    /**
     * 将完整 namespace 快照及 CRC32 写入 AtomicFile；超过容量则拒绝提交。
     * 整体快照配合原子替换，使断电或进程退出后只看到旧版或新版，
     * 不把只写入一半的记录集当作成功；失败时调用方也不更新缓存。
     */
    private static void commit(StoreAddress key, Namespace next) {
        AtomicFile atomic = file(key); FileOutputStream stream = null;
        try {
            // 1. 在内存中序列化完整候选快照，保留每个记录和 namespace 的版本。
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            DataOutputStream out = new DataOutputStream(bytes);
            out.writeInt(MAGIC); out.writeInt(VERSION); out.writeLong(next.highWater);
            out.writeInt(next.records.size());
            for (Map.Entry<String, StoredValue> e : next.records.entrySet()) {
                StoredValue v = e.getValue();
                out.writeUTF(e.getKey()); out.writeUTF(v.schemaId); out.writeInt(v.schemaVersion);
                out.writeUTF(v.encoding); out.writeLong(v.revision);
                out.writeInt(v.payload.length); out.write(v.payload);
            }
            // 2. CRC 覆盖正文，不含末尾的校验值本身；读取时使用相同边界。
            out.flush(); CRC32 crc = new CRC32(); byte[] content = bytes.toByteArray();
            crc.update(content); out.writeLong(crc.getValue()); out.flush();
            // 3. 写文件前就检查配额，超限时旧 AtomicFile 和缓存都不触碰。
            if (bytes.size() > MAX_FILE) throw new IllegalArgumentException("namespace file quota");
            // 4. 只有 finishWrite 成功后，调用方才会把 next 放入缓存。
            stream = atomic.startWrite(); stream.write(bytes.toByteArray());
            atomic.finishWrite(stream);
            TLog.d(TAG, "commit complete " + TLog.address(key) + " revision=" + next.highWater
                    + " bytes=" + bytes.size());
        } catch (IOException e) {
            TLog.e(TAG, "commit failed type=" + e.getClass().getSimpleName());
            // 写入过程中失败则恢复 AtomicFile 的旧版本，不能发布半成品。
            if (stream != null) atomic.failWrite(stream);
            throw new IllegalStateException("file commit failed", e);
        }
    }
    /** 从已验证的快照返回当前值；缺失时返回 namespace 高水位。 */
    @Override public synchronized Snapshot read(StoreAddress key) {
        Namespace ns = ns(key); StoredValue value = ns.records.get(key.key);
        TLog.d(TAG, "read " + TLog.address(key) + " present=" + (value != null)
                + " revision=" + (value == null ? ns.highWater : value.revision));
        return new Snapshot(value, value == null ? ns.highWater : value.revision);
    }
    /**
     * 在旧快照上检查 CAS，复制并提交新快照；文件成功后才替换缓存，
     * 避免本次进程读到一个实际未落盘、重启后又消失的“成功写入”。
     */
    @Override public synchronized Change write(StoreAddress key, StoredValue value, int mode, long expected) {
        Namespace current = ns(key); StoredValue old = current.records.get(key.key);
        // 1. 在复制快照前处理 CAS 冲突，失败不会生成新的文件候选。
        int status = Backend.precondition(old, current.highWater, mode, expected);
        if (status != Wire.OK) {
            TLog.d(TAG, "write precondition " + TLog.address(key) + " status=" + status);
            return new Change(status, 0, old == null ? current.highWater : old.revision);
        }
        // 2. next 是提交候选，current 在文件成功提交前保持不变。
        Namespace next = new Namespace(); next.records.putAll(current.records);
        next.highWater = current.highWater + 1;
        next.records.put(key.key, value.withRevision(next.highWater));
        try { commit(key, next); } catch (IllegalArgumentException quota) {
            TLog.d(TAG, "write quota " + TLog.address(key));
            return new Change(Wire.QUOTA_EXCEEDED, 0, current.highWater);
        }
        // 3. 文件提交成功后才让本进程后续读取看到新值。
        cache.put(key.namespaceId(), next);
        TLog.d(TAG, "write committed " + TLog.address(key) + " revision=" + next.highWater);
        return new Change(Wire.OK, old == null ? Wire.CREATE_OP : Wire.UPDATE_OP, next.highWater);
    }
    /** 删除记录后仍持久化递增的 highWater，防止旧 revision 在删除后重新有效。 */
    @Override public synchronized Change delete(StoreAddress key, long expected) {
        Namespace current = ns(key); StoredValue old = current.records.get(key.key);
        // 1. 不存在或版本不匹配时不创建文件，也不改变缓存。
        if (old == null) {
            TLog.d(TAG, "delete absent " + TLog.address(key) + " revision=" + current.highWater);
            return new Change(Wire.NOT_FOUND, 0, current.highWater);
        }
        if (expected >= 0 && expected != old.revision) {
            TLog.d(TAG, "delete conflict " + TLog.address(key) + " revision=" + old.revision);
            return new Change(Wire.CONFLICT, 0, old.revision);
        }
        // 2. 删除记录仍持久化更高的 namespace 版本，用于识别旧 CAS 快照。
        Namespace next = new Namespace(); next.records.putAll(current.records);
        next.records.remove(key.key); next.highWater = current.highWater + 1;
        // 3. 提交失败保持 current；成功后才原子地切换当前缓存引用。
        commit(key, next); cache.put(key.namespaceId(), next);
        TLog.d(TAG, "delete committed " + TLog.address(key) + " revision=" + next.highWater);
        return new Change(Wire.OK, Wire.DELETE_OP, next.highWater);
    }
}
