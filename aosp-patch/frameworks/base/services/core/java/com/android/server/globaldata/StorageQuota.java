package com.android.server.globaldata;

import java.nio.charset.StandardCharsets;

/** 三种后端的计费上限。元数据预算用于限制资源占用，与实际堆内存和文件大小不同。 */
final class StorageQuota {
    static final int MEMORY_RECORDS = 4096;
    static final int DATABASE_RECORDS = 16384;
    static final int RETAINED_NAMESPACES = 64;
    static final long MEMORY_PAYLOAD = 4L * 1024 * 1024;
    static final long MEMORY_METADATA = 4L * 1024 * 1024;
    static final long DATABASE_PAYLOAD = 16L * 1024 * 1024;
    static final long DATABASE_METADATA = 8L * 1024 * 1024;

    // 文件格式的条数和字节限制读写共用，保证已接受的快照可以重载。
    static final int FILE_RECORDS = 8192;
    static final int FILE_BYTES = 1024 * 1024;
    static final int FILE_CACHE_NAMESPACES = 16;
    static final long FILE_CACHE_BYTES = 8L * 1024 * 1024;
    // 固定预算覆盖空 payload 仍需分配的记录、索引和版本等元数据。
    static final int RECORD_OVERHEAD = 256;
    static final int NAMESPACE_OVERHEAD = 256;

    private StorageQuota() {}

    private static int bytes(String text) {
        return text.getBytes(StandardCharsets.UTF_8).length;
    }

    static long recordBytes(String owner, String namespace, String key, StoredValue value) {
        return RECORD_OVERHEAD + bytes(owner) + bytes(namespace) + bytes(key)
                + bytes(value.schemaId) + bytes(value.encoding);
    }

    static long namespaceBytes(String owner, String namespace) {
        return NAMESPACE_OVERHEAD + bytes(owner) + bytes(namespace);
    }
}
