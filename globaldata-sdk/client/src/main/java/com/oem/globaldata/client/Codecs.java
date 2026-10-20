package com.oem.globaldata.client;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;

/** 内置基本类型 Codec；固定 schema 与字节布局让不同 App 版本能互相解码。 */
public final class Codecs {
    /** 工具类不创建实例。 */
    private Codecs() {}
    /** 32 位有符号整数：4 字节大端格式，schema 版本 1。 */
    public static final ValueCodec<Integer> INT = new ValueCodec<Integer>() {
        /** 稳定类型 ID 防止把其他 4 字节数据误当成整数。 */
        public String schemaId() { return "primitive.int"; }
        /** 新写入值始终使用第 1 版布局。 */
        public int schemaVersion() { return 1; }
        /** 大端整数格式标识只供客户端/诊断使用，服务端不解码。 */
        public String encoding() { return "primitive-be-v1"; }
        /** ByteBuffer 默认大端，把 Java Integer 精确写成 4 字节。 */
        public byte[] encode(Integer value) { return ByteBuffer.allocate(4).putInt(value).array(); }
        /** 先拒绝不兼容版本或错误长度，避免读出错误数值。 */
        public Integer decode(byte[] bytes, int version) {
            if (version != 1 || bytes.length != 4) throw new IllegalArgumentException("int format");
            return ByteBuffer.wrap(bytes).getInt();
        }
    };
    /** 64 位有符号整数：8 字节大端格式，可精确保存超出 int 范围的计数。 */
    public static final ValueCodec<Long> LONG = new ValueCodec<Long>() {
        /** 与其他基础类型隔离的稳定 schema。 */
        public String schemaId() { return "primitive.long"; }
        /** 新写入值使用固定的第 1 版布局。 */
        public int schemaVersion() { return 1; }
        /** 服务端仅保存此格式标签，不参与数值计算。 */
        public String encoding() { return "primitive-be-v1"; }
        /** ByteBuffer 默认大端，把 long 的全部 64 位写入。 */
        public byte[] encode(Long value) { return ByteBuffer.allocate(8).putLong(value).array(); }
        /** 长度和版本不符时拒绝解码，避免把损坏值当成计数。 */
        public Long decode(byte[] bytes, int version) {
            if (version != 1 || bytes.length != 8) throw new IllegalArgumentException("long format");
            return ByteBuffer.wrap(bytes).getLong();
        }
    };
    /** 32 位 IEEE 754 浮点数：4 字节大端格式，包含 NaN、无穷大和带符号零。 */
    public static final ValueCodec<Float> FLOAT = new ValueCodec<Float>() {
        /** 与 int 区分，即使它们都使用 4 字节。 */
        public String schemaId() { return "primitive.float"; }
        /** 固定当前二进制布局的版本。 */
        public int schemaVersion() { return 1; }
        /** 明确字节序供跨 App 诊断。 */
        public String encoding() { return "primitive-be-v1"; }
        /** 使用 raw bits，避免普通 floatToIntBits 折叠 NaN 的位表示。 */
        public byte[] encode(Float value) {
            return ByteBuffer.allocate(4).putInt(Float.floatToRawIntBits(value)).array();
        }
        /** 按原始 IEEE 754 位模式恢复，包括 -0.0。 */
        public Float decode(byte[] bytes, int version) {
            if (version != 1 || bytes.length != 4) throw new IllegalArgumentException("float format");
            return Float.intBitsToFloat(ByteBuffer.wrap(bytes).getInt());
        }
    };
    /** 64 位 IEEE 754 浮点数：8 字节大端格式，适合需要更高精度的读写。 */
    public static final ValueCodec<Double> DOUBLE = new ValueCodec<Double>() {
        /** 与 long 区分，即使它们都使用 8 字节。 */
        public String schemaId() { return "primitive.double"; }
        /** 固定当前二进制布局的版本。 */
        public int schemaVersion() { return 1; }
        /** 明确字节序供跨 App 诊断。 */
        public String encoding() { return "primitive-be-v1"; }
        /** 使用 raw bits 保留 Java double 当前的 IEEE 754 位模式。 */
        public byte[] encode(Double value) {
            return ByteBuffer.allocate(8).putLong(Double.doubleToRawLongBits(value)).array();
        }
        /** 长度或版本不匹配时拒绝，否则按位恢复 double。 */
        public Double decode(byte[] bytes, int version) {
            if (version != 1 || bytes.length != 8) throw new IllegalArgumentException("double format");
            return Double.longBitsToDouble(ByteBuffer.wrap(bytes).getLong());
        }
    };
    /** 布尔值：单字节 0/1，其他字节视为损坏数据。 */
    public static final ValueCodec<Boolean> BOOLEAN = new ValueCodec<Boolean>() {
        /** 与整数和字符串使用不同 schema ID。 */
        public String schemaId() { return "primitive.boolean"; }
        /** 仅支持当前单字节版。 */
        public int schemaVersion() { return 1; }
        /** 原始二进制格式标签。 */
        public String encoding() { return "primitive-be-v1"; }
        /** true 写为 1，false 写为 0。 */
        public byte[] encode(Boolean value) { return new byte[] {(byte) (value ? 1 : 0)}; }
        /** 版本、长度和值均严格校验，避免非 0/1 数据被静默转换。 */
        public Boolean decode(byte[] bytes, int version) {
            if (version != 1 || bytes.length != 1 || (bytes[0] != 0 && bytes[0] != 1))
                throw new IllegalArgumentException("boolean format");
            return bytes[0] == 1;
        }
    };
    /** 字符串：UTF-8 字节，单值大小仍受 64 KiB 上限约束。 */
    public static final ValueCodec<String> STRING = new ValueCodec<String>() {
        /** 字符串的独立 schema ID。 */
        public String schemaId() { return "primitive.string"; }
        /** 当前 UTF-8 约定版本。 */
        public int schemaVersion() { return 1; }
        /** 明确编码，避免设备默认字符集不同导致乱码。 */
        public String encoding() { return "utf8-v1"; }
        /** 先编码成字节，再由 manager 检查 64 KiB 上限。 */
        public byte[] encode(String value) { return value.getBytes(StandardCharsets.UTF_8); }
        /** 只读取已知版本；未来格式变化需显式兼容旧值。 */
        public String decode(byte[] bytes, int version) {
            if (version != 1) throw new IllegalArgumentException("string version");
            return new String(bytes, StandardCharsets.UTF_8);
        }
    };
    /** 原始 byte[]；在实际编解码时复制，异步编码执行前调用方仍须保持输入不变。 */
    public static final ValueCodec<byte[]> BYTES = new ValueCodec<byte[]>() {
        /** 原始字节仍有独立 schema，不能与其他类型混读。 */
        public String schemaId() { return "primitive.bytes"; }
        /** 当前原样传输格式版本。 */
        public int schemaVersion() { return 1; }
        /** 不套 Java 原生对象序列化。 */
        public String encoding() { return "raw-v1"; }
        /** 编码时复制数组；编码开始前调用方应避免修改输入数组。 */
        public byte[] encode(byte[] value) { return Arrays.copyOf(value, value.length); }
        /** 验证版本并复制服务端返回的字节供调用方独立持有。 */
        public byte[] decode(byte[] bytes, int version) {
            if (version != 1) throw new IllegalArgumentException("bytes version");
            return Arrays.copyOf(bytes, bytes.length);
        }
    };
}
