package com.android.server.globaldata;

import android.os.Environment;
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
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.zip.CRC32;

/**
 * 文件后端：每个 scope/user/owner 使用一个有界 AtomicFile 快照。
 * 启动后按需加载并缓存；写入先构造新快照，文件原子提交成功后才替换内存缓存。
 * 按 owner 地址分文件可限制单次重写和损坏影响范围；保留高水位使重启后的 CAS
 * 仍可识别删除过的记录，而不会把旧快照当作全新 owner 地址。
 */
final class FileBackend implements Backend {
    /** 日志二级标识。 */
    private static final String TAG = "FileBackend";

    /**
     * 魔数/版本防止误把其他格式当作当前快照；1 MiB 上限约束加载时内存
     * 和整文件重写成本，避免异常客户端让 system_server 处理无界文件。
     */
    private static final int MAGIC = 0x47444631, VERSION = 1;
    private static final int HEADER_BYTES = 20, CHECKSUM_BYTES = Long.BYTES;
    private final Function<StoreAddress, File> directories;
    /** 读命中或成功提交后更新访问顺序，失败写入不改变淘汰顺序。 */
    private final Map<String, OwnerSnapshot> cache = new LinkedHashMap<>(16, 0.75f, true);
    private long cachedBytes;

    FileBackend() {
        this(FileBackend::directory);
    }

    FileBackend(Function<StoreAddress, File> directories) {
        this.directories = directories;
    }
    /** records 保存当前值，highWater 保存成功变更的最大版本。 */
    private static final class OwnerSnapshot {
        final Map<String, StoredValue> records = new HashMap<>();
        long highWater;
        long cacheBytes;
    }
    /** 使用 Android 系统规定的 DE/CE 目录，才能继承正确的用户隔离与解锁行为。 */
    private static File directory(StoreAddress key) {
        File parent = key.scope == Wire.USER_CE
                ? Environment.getDataSystemCeDirectory(key.userId)
                : Environment.getDataSystemDeDirectory(key.userId);
        return new File(parent, "globaldata/v2/file");
    }
    /**
     * 用 ownerId 的稳定 SHA-256 摘要命名文件：不同地址映射到不同快照，
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
    AtomicFile file(StoreAddress key) {
        File dir = directories.apply(key);
        return new AtomicFile(new File(dir, hash(key.ownerId()) + ".gdf"));
    }

    private void cache(StoreAddress key, OwnerSnapshot snapshot) {
        String id = key.ownerId();
        OwnerSnapshot previous = cache.remove(id);
        if (previous != null) cachedBytes -= previous.cacheBytes;
        snapshot.cacheBytes = StorageQuota.ownerBytes(key.owner);
        for (Map.Entry<String, StoredValue> entry : snapshot.records.entrySet()) {
            StoredValue value = entry.getValue();
            snapshot.cacheBytes += StorageQuota.recordBytes(key.owner,
                    entry.getKey(), value) + value.payload.length;
        }
        // 缓存空间不足只影响命中率，后续读取仍可恢复已提交的快照和版本。
        if (snapshot.cacheBytes > StorageQuota.FILE_CACHE_BYTES) return;
        cache.put(id, snapshot);
        cachedBytes += snapshot.cacheBytes;
        Iterator<OwnerSnapshot> oldest = cache.values().iterator();
        while (cache.size() > StorageQuota.FILE_CACHE_OWNERS
                || cachedBytes > StorageQuota.FILE_CACHE_BYTES) {
            cachedBytes -= oldest.next().cacheBytes;
            oldest.remove();
        }
    }
    /**
     * 首次访问时从 AtomicFile 恢复快照：校验大小、格式、记录数及 CRC。
     * 损坏时抛出存储异常，不把空快照写入缓存；否则下一次写入可能覆盖
     * 本来还能诊断或恢复的旧数据。CRC 用于发现偶发损坏，不承担防篡改认证。
     */
    private OwnerSnapshot snapshot(StoreAddress key, boolean cacheRead) {
        String id = key.ownerId();
        if (cacheRead) {
            OwnerSnapshot found = cache.get(id);
            if (found != null) return found;
        } else {
            // 写入可能失败，查找候选快照时先不触发访问顺序更新。
            for (Map.Entry<String, OwnerSnapshot> entry : cache.entrySet()) {
                if (entry.getKey().equals(id)) return entry.getValue();
            }
        }

        AtomicFile file = file(key);
        try {
            File committed = StorageFiles.committedSource(file);
            // 未写入的地址没有磁盘快照，避免只读查询挤掉真实缓存。
            if (committed == null) return new OwnerSnapshot();
            OwnerSnapshot loaded;
            try (FileInputStream in = new FileInputStream(committed)) {
                byte[] bytes = in.readNBytes(StorageQuota.FILE_BYTES + 1);
                loaded = decode(bytes);
            }
            if (cacheRead) cache(key, loaded);
            return loaded;
        } catch (IOException e) {
            TLog.e(TAG, "load failed type=" + e.getClass().getSimpleName());
            throw new IllegalStateException("file store unavailable", e);
        }
    }

    private static OwnerSnapshot decode(byte[] bytes) throws IOException {
        if (bytes.length < HEADER_BYTES + CHECKSUM_BYTES || bytes.length > StorageQuota.FILE_BYTES) {
            throw new IOException("invalid snapshot size");
        }
        int crcStart = bytes.length - CHECKSUM_BYTES;
        CRC32 crc = new CRC32();
        crc.update(bytes, 0, crcStart);
        try (DataInputStream trailer = new DataInputStream(
                new ByteArrayInputStream(bytes, crcStart, CHECKSUM_BYTES))) {
            if (trailer.readLong() != crc.getValue()) throw new IOException("checksum mismatch");
        }

        OwnerSnapshot loaded = new OwnerSnapshot();
        try (DataInputStream input = new DataInputStream(new ByteArrayInputStream(bytes, 0, crcStart))) {
            if (input.readInt() != MAGIC || input.readInt() != VERSION) {
                throw new IOException("unknown file version");
            }
            loaded.highWater = input.readLong();
            int count = input.readInt();
            if (loaded.highWater <= 0 || count < 0 || count > StorageQuota.FILE_RECORDS
                    || count > loaded.highWater) {
                throw new IOException("invalid snapshot revision or count");
            }
            Set<Long> revisions = new HashSet<>();
            for (int i = 0; i < count; i++) {
                String key = input.readUTF();
                String schema = input.readUTF();
                int version = input.readInt();
                String encoding = input.readUTF();
                long revision = input.readLong();
                int size = input.readInt();
                if (!StoreAddress.name(key, 180) || !StoreAddress.name(schema, 128)
                        || !StoreAddress.name(encoding, 64) || version < 1
                        || revision <= 0 || revision > loaded.highWater || !revisions.add(revision)
                        || size < 0 || size > Wire.MAX_PAYLOAD || size > input.available()) {
                    throw new IOException("invalid snapshot record");
                }
                byte[] payload = new byte[size];
                input.readFully(payload);
                if (loaded.records.put(key, new StoredValue(schema, version, encoding,
                        payload, revision)) != null) {
                    throw new IOException("duplicate snapshot key");
                }
            }
            if (input.read() != -1) throw new IOException("trailing snapshot data");
        }
        return loaded;
    }
    /**
     * 将完整 owner 地址的快照及 CRC32 写入 AtomicFile；超过容量则拒绝提交。
     * 整体快照配合原子替换，使断电或进程退出后只看到旧版或新版，
     * 不把只写入一半的记录集当作成功；失败时调用方也不更新缓存。
     */
    private boolean commit(StoreAddress key, OwnerSnapshot next) {
        if (next.records.size() > StorageQuota.FILE_RECORDS) return false;
        AtomicFile atomic = file(key);
        FileOutputStream stream = null;
        try {
            // 1. 在内存中序列化完整候选快照，保留每个记录和 owner 地址的版本。
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
            if (bytes.size() > StorageQuota.FILE_BYTES) return false;
            StorageFiles.prepareDirectory(atomic.getBaseFile().getParentFile());
            // 4. 只有 finishWrite 成功后，调用方才会把 next 放入缓存。
            stream = atomic.startWrite(); stream.write(bytes.toByteArray());
            StorageFiles.finishWrite(atomic, stream);
            TLog.d(TAG, "commit complete " + TLog.address(key) + " revision=" + next.highWater
                    + " bytes=" + bytes.size());
            return true;
        } catch (IOException e) {
            TLog.e(TAG, "commit failed type=" + e.getClass().getSimpleName());
            // 写入过程中失败则恢复 AtomicFile 的旧版本，不能发布半成品。
            if (stream != null) atomic.failWrite(stream);
            throw new IllegalStateException("file commit failed", e);
        }
    }
    /** 从已验证的快照返回当前值；缺失时返回 owner 地址的高水位。 */
    @Override public synchronized Snapshot read(StoreAddress key) {
        OwnerSnapshot current = snapshot(key, true);
        StoredValue value = current.records.get(key.key);
        TLog.d(TAG, "read " + TLog.address(key) + " present=" + (value != null)
                + " revision=" + (value == null ? current.highWater : value.revision));
        return new Snapshot(value, value == null ? current.highWater : value.revision);
    }
    /**
     * 在旧快照上检查 CAS，复制并提交新快照；文件成功后才替换缓存，
     * 避免本次进程读到一个实际未落盘、重启后又消失的“成功写入”。
     */
    @Override public synchronized Change write(StoreAddress key, StoredValue value, int mode, long expected) {
        OwnerSnapshot current = snapshot(key, false); StoredValue old = current.records.get(key.key);
        // 1. 在复制快照前处理 CAS 冲突，失败不会生成新的文件候选。
        int status = Backend.precondition(old, current.highWater, mode, expected);
        if (status != Wire.OK) {
            TLog.d(TAG, "write precondition " + TLog.address(key) + " status=" + status);
            return new Change(status, 0, old == null ? current.highWater : old.revision);
        }
        if (old == null && current.records.size() >= StorageQuota.FILE_RECORDS) {
            return new Change(Wire.QUOTA_EXCEEDED, 0, current.highWater);
        }
        // 2. next 是提交候选，current 在文件成功提交前保持不变。
        OwnerSnapshot next = new OwnerSnapshot(); next.records.putAll(current.records);
        next.highWater = Math.incrementExact(current.highWater);
        next.records.put(key.key, value.withRevision(next.highWater));
        if (!commit(key, next)) {
            TLog.d(TAG, "write quota " + TLog.address(key));
            return new Change(Wire.QUOTA_EXCEEDED, 0, old == null ? current.highWater : old.revision);
        }
        // 3. 文件提交成功后才让本进程后续读取看到新值。
        cache(key, next);
        TLog.d(TAG, "write committed " + TLog.address(key) + " revision=" + next.highWater);
        return new Change(Wire.OK, old == null ? Wire.CREATE_OP : Wire.UPDATE_OP, next.highWater);
    }
    /** 删除记录后仍持久化递增的 highWater，防止旧 revision 在删除后重新有效。 */
    @Override public synchronized Change delete(StoreAddress key, long expected) {
        OwnerSnapshot current = snapshot(key, false); StoredValue old = current.records.get(key.key);
        // 1. 不存在或版本不匹配时不创建文件，也不改变缓存。
        if (old == null) {
            TLog.d(TAG, "delete absent " + TLog.address(key) + " revision=" + current.highWater);
            return new Change(Wire.NOT_FOUND, 0, current.highWater);
        }
        if (expected >= 0 && expected != old.revision) {
            TLog.d(TAG, "delete conflict " + TLog.address(key) + " revision=" + old.revision);
            return new Change(Wire.CONFLICT, 0, old.revision);
        }
        // 2. 删除记录仍持久化更高的 owner 地址 版本，用于识别旧 CAS 快照。
        OwnerSnapshot next = new OwnerSnapshot(); next.records.putAll(current.records);
        next.records.remove(key.key);
        next.highWater = Math.incrementExact(current.highWater);
        // 3. 提交失败保持 current；成功后才原子地切换当前缓存引用。
        if (!commit(key, next)) return new Change(Wire.QUOTA_EXCEEDED, 0, old.revision);
        cache(key, next);
        TLog.d(TAG, "delete committed " + TLog.address(key) + " revision=" + next.highWater);
        return new Change(Wire.OK, Wire.DELETE_OP, next.highWater);
    }
}
