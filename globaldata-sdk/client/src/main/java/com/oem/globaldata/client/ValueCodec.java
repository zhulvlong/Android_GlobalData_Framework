package com.oem.globaldata.client;

/**
 * App 侧业务类型与字节之间的稳定契约。system_server 只保存元数据和 byte[]，
 * 不加载业务类；读写双方必须约定一致的 schema 和版本兼容策略。
 * @param <T> 客户端业务值类型
 */
public interface ValueCodec<T> {
    /** @return 业务类型的稳定标识；读取时 SDK 会检查它与 DataKey 的 Codec 一致 */
    String schemaId();
    /** @return 本 Codec 新写入值所使用的正整数 schema 版本 */
    int schemaVersion();
    /** @return 字节格式标识；服务端保存但不负责解析 */
    String encoding();
    /**
     * 在 App 侧把业务值转成最多 64 KiB 的非 null 字节数组。
     * @param value 待写入的业务值
     * @return 可跨进程传输的字节
     * @throws Exception 编码失败；SDK 把非 GlobalDataException 包装为 BAD_REQUEST
     */
    byte[] encode(T value) throws Exception;
    /**
     * 按实际存储版本在 App 侧恢复业务值，需自行兼容允许读取的旧格式。
     * @param bytes 服务端保存的原始字节
     * @param storedVersion 写入该值时的 schema 版本
     * @return 解码后的业务对象
     * @throws Exception 格式不兼容或字节损坏
     */
    T decode(byte[] bytes, int storedVersion) throws Exception;
}
