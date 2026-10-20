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

/** One bounded AtomicFile snapshot per verified owner/scope/namespace. */
final class FileBackend implements Backend {
    private static final int MAGIC = 0x47444631, VERSION = 1, MAX_FILE = 1024 * 1024;
    private final Map<String, Namespace> cache = new HashMap<>();
    private static final class Namespace {
        final Map<String, StoredValue> records = new HashMap<>();
        long highWater;
    }
    private static File directory(StoreAddress key) {
        File parent = key.scope == Wire.USER_CE
                ? Environment.getDataSystemCeDirectory(key.userId)
                : Environment.getDataSystemDeDirectory(key.userId);
        return new File(parent, "globaldata/file");
    }
    private static String hash(String identity) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(
                    identity.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            StringBuilder out = new StringBuilder();
            for (byte b : digest) out.append(String.format(java.util.Locale.ROOT, "%02x", b & 0xff));
            return out.toString();
        } catch (Exception e) { throw new IllegalStateException(e); }
    }
    private static AtomicFile file(StoreAddress key) {
        File dir = directory(key);
        if (!dir.exists()) {
            if (!dir.mkdirs() || !SELinux.restoreconRecursive(dir))
                throw new IllegalStateException("cannot create file store");
        }
        return new AtomicFile(new File(dir, hash(key.namespaceId()) + ".gdf"));
    }
    private Namespace ns(StoreAddress key) {
        Namespace found = cache.get(key.namespaceId());
        if (found != null) return found;
        AtomicFile file = file(key); Namespace loaded = new Namespace();
        if (file.getBaseFile().exists()
                || new File(file.getBaseFile().getPath() + ".bak").exists()) {
            try (FileInputStream in = file.openRead()) {
                ByteArrayOutputStream out = new ByteArrayOutputStream();
                byte[] block = new byte[8192]; int count;
                while ((count = in.read(block)) != -1) {
                    out.write(block, 0, count);
                    if (out.size() > MAX_FILE) throw new IOException("file too large");
                }
                byte[] bytes = out.toByteArray();
                if (bytes.length < 20) throw new IOException("truncated file");
                DataInputStream input = new DataInputStream(new ByteArrayInputStream(bytes));
                if (input.readInt() != MAGIC || input.readInt() != VERSION)
                    throw new IOException("unknown file version");
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
                int crcStart = bytes.length - 8;
                if (input.available() != 8) throw new IOException("trailing data");
                long saved = input.readLong(); CRC32 crc = new CRC32(); crc.update(bytes, 0, crcStart);
                if (saved != crc.getValue()) throw new IOException("checksum mismatch");
            } catch (IOException e) { throw new IllegalStateException("file store unavailable", e); }
        }
        cache.put(key.namespaceId(), loaded); return loaded;
    }
    private static void commit(StoreAddress key, Namespace next) {
        AtomicFile atomic = file(key); FileOutputStream stream = null;
        try {
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
            out.flush(); CRC32 crc = new CRC32(); byte[] content = bytes.toByteArray();
            crc.update(content); out.writeLong(crc.getValue()); out.flush();
            if (bytes.size() > MAX_FILE) throw new IllegalArgumentException("namespace file quota");
            stream = atomic.startWrite(); stream.write(bytes.toByteArray());
            atomic.finishWrite(stream);
        } catch (IOException e) {
            if (stream != null) atomic.failWrite(stream);
            throw new IllegalStateException("file commit failed", e);
        }
    }
    @Override public synchronized Snapshot read(StoreAddress key) {
        Namespace ns = ns(key); StoredValue value = ns.records.get(key.key);
        return new Snapshot(value, value == null ? ns.highWater : value.revision);
    }
    @Override public synchronized Change write(StoreAddress key, StoredValue value, int mode, long expected) {
        Namespace current = ns(key); StoredValue old = current.records.get(key.key);
        int status = Backend.precondition(old, current.highWater, mode, expected);
        if (status != Wire.OK) return new Change(status, 0,
                old == null ? current.highWater : old.revision);
        Namespace next = new Namespace(); next.records.putAll(current.records);
        next.highWater = current.highWater + 1;
        next.records.put(key.key, value.withRevision(next.highWater));
        try { commit(key, next); } catch (IllegalArgumentException quota) {
            return new Change(Wire.QUOTA_EXCEEDED, 0, current.highWater);
        }
        cache.put(key.namespaceId(), next);
        return new Change(Wire.OK, old == null ? Wire.CREATE_OP : Wire.UPDATE_OP, next.highWater);
    }
    @Override public synchronized Change delete(StoreAddress key, long expected) {
        Namespace current = ns(key); StoredValue old = current.records.get(key.key);
        if (old == null) return new Change(Wire.NOT_FOUND, 0, current.highWater);
        if (expected >= 0 && expected != old.revision)
            return new Change(Wire.CONFLICT, 0, old.revision);
        Namespace next = new Namespace(); next.records.putAll(current.records);
        next.records.remove(key.key); next.highWater = current.highWater + 1;
        commit(key, next); cache.put(key.namespaceId(), next);
        return new Change(Wire.OK, Wire.DELETE_OP, next.highWater);
    }
}
