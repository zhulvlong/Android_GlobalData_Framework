package com.android.server.globaldata;

import android.os.Bundle;
import com.oem.globaldata.ipc.Wire;
import java.util.Arrays;

/**
 * 服务端保存的已编码值。服务端不解释业务对象，只保存 schema 元信息和字节；
 * revision 由存储后端在成功提交时分配。
 * 这样业务对象与 Codec 可随 App 升级，系统镜像不必链接任何设备业务类。
 */
final class StoredValue {
    /** schemaId/encoding 描述解码方式；schemaVersion 指出写入时的业务格式版本。 */
    final String schemaId, encoding;
    final int schemaVersion;
    /**
     * 编码 payload；构造和返回 Bundle 时复制，防止可变数组在校验、提交或回调间
     * 被其他持有者修改，使版本号对应的字节内容保持确定。
     */
    final byte[] payload;
    /** 当前值的后端版本，Binder 写入请求中初始为 0。 */
    final long revision;
    StoredValue(String schemaId, int schemaVersion, String encoding, byte[] payload, long revision) {
        this.schemaId = schemaId; this.schemaVersion = schemaVersion; this.encoding = encoding;
        this.payload = Arrays.copyOf(payload, payload.length); this.revision = revision;
    }
    /**
     * 在进入后端前校验 schema、编码和最大载荷；即使 SDK 已检查，服务端仍须
     * 防御直接调用 Binder 的客户端，避免无效或过大的字节进入持久化格式。
     */
    static StoredValue parse(Bundle b) {
        if (b == null) throw new IllegalArgumentException("missing value");
        // 1. 只取 schema 元信息和原始字节；服务端不实例化 App 的业务对象。
        String schema = b.getString(Wire.SCHEMA_ID), encoding = b.getString(Wire.ENCODING);
        byte[] payload = b.getByteArray(Wire.PAYLOAD);
        int version = b.getInt(Wire.SCHEMA_VERSION);
        // 2. 在分配后端记录前验证版本和大小，阻断直接调用 Binder 的畸形输入。
        if (!StoreAddress.name(schema, 128) || !StoreAddress.name(encoding, 64)
                || version < 1 || payload == null || payload.length > Wire.MAX_PAYLOAD)
            throw new IllegalArgumentException("invalid encoded value");
        // 3. 构造函数复制 payload，提交期间不共享来自 Bundle 的可变数组。
        return new StoredValue(schema, version, encoding, payload, 0);
    }
    /** 生成携带新 revision 的独立值对象，供后端提交。 */
    StoredValue withRevision(long next) {
        return new StoredValue(schemaId, schemaVersion, encoding, payload, next);
    }
    /** 返回传输用 Bundle；再次复制 payload，避免结果调用方修改内部字节。 */
    Bundle wire() {
        // 返回新的 Bundle 和字节副本，不把后端持有的 payload 数组直接交出去。
        Bundle b = new Bundle(); b.putString(Wire.SCHEMA_ID, schemaId);
        b.putInt(Wire.SCHEMA_VERSION, schemaVersion); b.putString(Wire.ENCODING, encoding);
        b.putByteArray(Wire.PAYLOAD, Arrays.copyOf(payload, payload.length)); return b;
    }
}
