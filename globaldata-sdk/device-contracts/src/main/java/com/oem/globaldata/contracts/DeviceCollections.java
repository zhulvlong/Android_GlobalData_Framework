package com.oem.globaldata.contracts;

import com.oem.globaldata.client.ValueCodec;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.json.JSONArray;
import org.json.JSONObject;

/**
 * 集合类型的显式 Codec 示例。只把 JSON 字节交给系统服务，系统服务无需反序列化业务对象。
 * 这样可避免 Java 原生反序列化把任意对象构造逻辑带进 system_server。
 */
public final class DeviceCollections {
    /** 每种集合格式具有独立的 Codec 和 schema，不实例化工具类。 */
    private DeviceCollections() {}

    /** 字符串列表的 v1 JSON 数组 Codec；集合元素必须是字符串。 */
    public static final ValueCodec<List<String>> STRING_LIST = new ValueCodec<List<String>>() {
        /** {@inheritDoc} 与其他业务类型区分的 schema 标识。 */
        public String schemaId() { return "com.oem.device.string-list"; }
        /** {@inheritDoc} 当前列表编码格式的版本。 */
        public int schemaVersion() { return 1; }
        /** {@inheritDoc} 列表序列化为 JSON，再以 UTF-8 传输。 */
        public String encoding() { return "json-utf8"; }
        /** {@inheritDoc} JSONArray 逐项保留列表顺序。 */
        public byte[] encode(List<String> value) { return new JSONArray(value).toString().getBytes(StandardCharsets.UTF_8); }
        /**
         * 将已保存的 v1 JSON 数组还原为字符串列表。
         *
         * @param bytes 存储的 UTF-8 字节
         * @param version 存储值携带的 schema 版本
         * @return 保持原有顺序的新列表
         * @throws Exception 版本不符或 JSON 内容无法解析时抛出
         */
        public List<String> decode(byte[] bytes, int version) throws Exception {
            // 未定义的版本不可按 v1 结构猜测解析。
            if (version != 1) throw new IllegalArgumentException("list version");
            JSONArray array = new JSONArray(new String(bytes, StandardCharsets.UTF_8));
            List<String> out = new ArrayList<>();
            // 拷贝到普通 Java 集合，避免把可变的 JSON 容器暴露给业务层。
            for (int i = 0; i < array.length(); i++) out.add(array.getString(i));
            return out;
        }
    };

    /** 字符串到字符串映射的 v1 JSON 对象 Codec；不承诺 Map 遍历顺序。 */
    public static final ValueCodec<Map<String, String>> STRING_MAP = new ValueCodec<Map<String, String>>() {
        /** {@inheritDoc} 映射与列表使用不同的类型标识。 */
        public String schemaId() { return "com.oem.device.string-map"; }
        /** {@inheritDoc} 当前映射编码格式的版本。 */
        public int schemaVersion() { return 1; }
        /** {@inheritDoc} 对象以 JSON/UTF-8 传输。 */
        public String encoding() { return "json-utf8"; }
        /** {@inheritDoc} Map 键和值均由本契约约束为字符串。 */
        public byte[] encode(Map<String, String> value) { return new JSONObject(value).toString().getBytes(StandardCharsets.UTF_8); }
        /**
         * 将已保存的 v1 JSON 对象还原为字符串映射。
         *
         * @param bytes 存储的 UTF-8 字节
         * @param version 存储值携带的 schema 版本
         * @return 可由业务层修改的新 Map
         * @throws Exception 版本不符或 JSON 字段无法读取时抛出
         */
        public Map<String, String> decode(byte[] bytes, int version) throws Exception {
            // 禁止把未知 schema 的数据误读为当前 Map 格式。
            if (version != 1) throw new IllegalArgumentException("map version");
            JSONObject json = new JSONObject(new String(bytes, StandardCharsets.UTF_8));
            Map<String, String> out = new HashMap<>();
            // 每个 JSON 属性转成 Map 项；属性顺序不属于协议。
            for (java.util.Iterator<String> it = json.keys(); it.hasNext();) {
                String key = it.next(); out.put(key, json.getString(key));
            }
            return out;
        }
    };

    /** 字符串集合的 v1 JSON 数组 Codec；Set 不保证业务遍历顺序。 */
    public static final ValueCodec<Set<String>> STRING_SET = new ValueCodec<Set<String>>() {
        /** Set 与 List 即使都写成 JSON 数组，也必须有不同 schema。 */
        public String schemaId() { return "com.oem.device.string-set"; }
        /** 新值统一按确定顺序写入第 1 版格式。 */
        public int schemaVersion() { return 1; }
        /** 系统只保存 JSON/UTF-8 字节，不解析集合。 */
        public String encoding() { return "json-utf8"; }
        /**
         * 排序后编码，避免 HashSet 的遍历顺序使同一组元素产生不同字节。
         * 空 Set 写为 []；null 元素不是本业务契约允许的字符串。
         */
        public byte[] encode(Set<String> value) {
            List<String> sorted = new ArrayList<>(value.size());
            for (String item : value) {
                if (item == null) throw new IllegalArgumentException("set contains null");
                sorted.add(item);
            }
            Collections.sort(sorted);
            return new JSONArray(sorted).toString().getBytes(StandardCharsets.UTF_8);
        }
        /**
         * 仅接受 v1 的字符串数组；重复项代表损坏或不符合 Set 契约的载荷。
         * @param bytes 存储的 UTF-8 JSON 字节
         * @param version 存储值携带的 schema 版本
         * @return 新建的 Set；元素是否存在有意义，迭代顺序不属于协议
         * @throws Exception 格式、版本或元素不符合本契约
         */
        public Set<String> decode(byte[] bytes, int version) throws Exception {
            if (version != 1) throw new IllegalArgumentException("set version");
            JSONArray array = new JSONArray(new String(bytes, StandardCharsets.UTF_8));
            Set<String> out = new LinkedHashSet<>();
            for (int i = 0; i < array.length(); i++) {
                Object item = array.get(i);
                if (!(item instanceof String) || !out.add((String) item))
                    throw new IllegalArgumentException("set item must be a unique string");
            }
            return out;
        }
    };

    /** 长度前缀采用 4 字节大端，单个元素继续使用对应的基础类型 Codec。 */
    private static void writeFrame(java.io.DataOutputStream out, byte[] bytes) throws java.io.IOException {
        out.writeInt(bytes.length);
        out.write(bytes);
    }

    /** 先验证长度再分配，损坏载荷不能让解码器读过边界。 */
    private static byte[] readFrame(java.io.DataInputStream in) throws java.io.IOException {
        int length = in.readInt();
        if (length < 0 || length > in.available()) throw new IllegalArgumentException("collection frame length");
        byte[] value = new byte[length];
        in.readFully(value);
        return value;
    }

    private static int readCount(java.io.DataInputStream in, int totalBytes, int framesPerEntry)
            throws java.io.IOException {
        int count = in.readInt();
        if (count < 0 || count > (totalBytes - 4) / (4 * framesPerEntry))
            throw new IllegalArgumentException("collection count");
        return count;
    }

    private static void requireEnd(java.io.DataInputStream in) throws java.io.IOException {
        if (in.available() != 0) throw new IllegalArgumentException("collection trailing bytes");
    }

    private static int compareUnsigned(byte[] left, byte[] right) {
        for (int i = 0; i < Math.min(left.length, right.length); i++) {
            int compared = Integer.compare(left[i] & 0xff, right[i] & 0xff);
            if (compared != 0) return compared;
        }
        return Integer.compare(left.length, right.length);
    }

    private static String readMapKey(byte[] bytes) throws java.nio.charset.CharacterCodingException {
        return StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(java.nio.charset.CodingErrorAction.REPORT)
                .onUnmappableCharacter(java.nio.charset.CodingErrorAction.REPORT)
                .decode(java.nio.ByteBuffer.wrap(bytes)).toString();
    }

    /** Integer 列表的独立 v1 Codec，保留元素原有次序。 */
    public static final ValueCodec<List<Integer>> INT_LIST = new ValueCodec<List<Integer>>() {
        public String schemaId() { return "com.oem.device.int-list"; }
        public int schemaVersion() { return 1; }
        public String encoding() { return "length-prefixed-be-v1"; }
        public byte[] encode(List<Integer> value) throws Exception {
            java.io.ByteArrayOutputStream buffer = new java.io.ByteArrayOutputStream();
            java.io.DataOutputStream out = new java.io.DataOutputStream(buffer);
            out.writeInt(value.size());
            for (Integer item : value) writeFrame(out, com.oem.globaldata.client.Codecs.INT.encode(java.util.Objects.requireNonNull(item, "list item")));
            return buffer.toByteArray();
        }
        public List<Integer> decode(byte[] bytes, int version) throws Exception {
            if (version != 1) throw new IllegalArgumentException("int list version");
            java.io.DataInputStream in = new java.io.DataInputStream(new java.io.ByteArrayInputStream(bytes));
            int count = readCount(in, bytes.length, 1);
            List<Integer> result = new ArrayList<>(count);
            for (int i = 0; i < count; i++) result.add(com.oem.globaldata.client.Codecs.INT.decode(readFrame(in), 1));
            requireEnd(in);
            return result;
        }
    };

    /** String 键、Integer 值映射的独立 v1 Codec，按键排序后写入。 */
    public static final ValueCodec<Map<String, Integer>> STRING_INT_MAP =
            new ValueCodec<Map<String, Integer>>() {
        public String schemaId() { return "com.oem.device.string-int-map"; }
        public int schemaVersion() { return 1; }
        public String encoding() { return "length-prefixed-be-v1"; }
        public byte[] encode(Map<String, Integer> value) throws Exception {
            java.io.ByteArrayOutputStream buffer = new java.io.ByteArrayOutputStream();
            java.io.DataOutputStream out = new java.io.DataOutputStream(buffer);
            List<String> keys = new ArrayList<>(value.keySet());
            for (String key : keys) java.util.Objects.requireNonNull(key, "map key");
            Collections.sort(keys);
            out.writeInt(keys.size());
            for (String key : keys) {
                writeFrame(out, key.getBytes(StandardCharsets.UTF_8));
                writeFrame(out, com.oem.globaldata.client.Codecs.INT.encode(java.util.Objects.requireNonNull(value.get(key), "map value")));
            }
            return buffer.toByteArray();
        }
        public Map<String, Integer> decode(byte[] bytes, int version) throws Exception {
            if (version != 1) throw new IllegalArgumentException("int map version");
            java.io.DataInputStream in = new java.io.DataInputStream(new java.io.ByteArrayInputStream(bytes));
            int count = readCount(in, bytes.length, 2);
            Map<String, Integer> result = new java.util.LinkedHashMap<>();
            for (int i = 0; i < count; i++) {
                String key = readMapKey(readFrame(in));
                Integer item = com.oem.globaldata.client.Codecs.INT.decode(readFrame(in), 1);
                if (result.putIfAbsent(key, item) != null)
                    throw new IllegalArgumentException("duplicate map key");
            }
            requireEnd(in);
            return result;
        }
    };

    /** Integer Set 的独立 v1 Codec，元素按无符号字节序排序但不承诺迭代顺序。 */
    public static final ValueCodec<Set<Integer>> INT_SET = new ValueCodec<Set<Integer>>() {
        public String schemaId() { return "com.oem.device.int-set"; }
        public int schemaVersion() { return 1; }
        public String encoding() { return "length-prefixed-be-v1"; }
        public byte[] encode(Set<Integer> value) throws Exception {
            List<byte[]> frames = new ArrayList<>(value.size());
            for (Integer item : value) frames.add(com.oem.globaldata.client.Codecs.INT.encode(java.util.Objects.requireNonNull(item, "set item")));
            frames.sort(DeviceCollections::compareUnsigned);
            java.io.ByteArrayOutputStream buffer = new java.io.ByteArrayOutputStream();
            java.io.DataOutputStream out = new java.io.DataOutputStream(buffer);
            out.writeInt(frames.size());
            for (byte[] frame : frames) writeFrame(out, frame);
            return buffer.toByteArray();
        }
        public Set<Integer> decode(byte[] bytes, int version) throws Exception {
            if (version != 1) throw new IllegalArgumentException("int set version");
            java.io.DataInputStream in = new java.io.DataInputStream(new java.io.ByteArrayInputStream(bytes));
            int count = readCount(in, bytes.length, 1);
            Set<Integer> result = new LinkedHashSet<>();
            for (int i = 0; i < count; i++) {
                if (!result.add(com.oem.globaldata.client.Codecs.INT.decode(readFrame(in), 1)))
                    throw new IllegalArgumentException("duplicate set item");
            }
            requireEnd(in);
            return result;
        }
    };

    /** Long 列表的独立 v1 Codec，保留元素原有次序。 */
    public static final ValueCodec<List<Long>> LONG_LIST = new ValueCodec<List<Long>>() {
        public String schemaId() { return "com.oem.device.long-list"; }
        public int schemaVersion() { return 1; }
        public String encoding() { return "length-prefixed-be-v1"; }
        public byte[] encode(List<Long> value) throws Exception {
            java.io.ByteArrayOutputStream buffer = new java.io.ByteArrayOutputStream();
            java.io.DataOutputStream out = new java.io.DataOutputStream(buffer);
            out.writeInt(value.size());
            for (Long item : value) writeFrame(out, com.oem.globaldata.client.Codecs.LONG.encode(java.util.Objects.requireNonNull(item, "list item")));
            return buffer.toByteArray();
        }
        public List<Long> decode(byte[] bytes, int version) throws Exception {
            if (version != 1) throw new IllegalArgumentException("long list version");
            java.io.DataInputStream in = new java.io.DataInputStream(new java.io.ByteArrayInputStream(bytes));
            int count = readCount(in, bytes.length, 1);
            List<Long> result = new ArrayList<>(count);
            for (int i = 0; i < count; i++) result.add(com.oem.globaldata.client.Codecs.LONG.decode(readFrame(in), 1));
            requireEnd(in);
            return result;
        }
    };

    /** String 键、Long 值映射的独立 v1 Codec，按键排序后写入。 */
    public static final ValueCodec<Map<String, Long>> STRING_LONG_MAP =
            new ValueCodec<Map<String, Long>>() {
        public String schemaId() { return "com.oem.device.string-long-map"; }
        public int schemaVersion() { return 1; }
        public String encoding() { return "length-prefixed-be-v1"; }
        public byte[] encode(Map<String, Long> value) throws Exception {
            java.io.ByteArrayOutputStream buffer = new java.io.ByteArrayOutputStream();
            java.io.DataOutputStream out = new java.io.DataOutputStream(buffer);
            List<String> keys = new ArrayList<>(value.keySet());
            for (String key : keys) java.util.Objects.requireNonNull(key, "map key");
            Collections.sort(keys);
            out.writeInt(keys.size());
            for (String key : keys) {
                writeFrame(out, key.getBytes(StandardCharsets.UTF_8));
                writeFrame(out, com.oem.globaldata.client.Codecs.LONG.encode(java.util.Objects.requireNonNull(value.get(key), "map value")));
            }
            return buffer.toByteArray();
        }
        public Map<String, Long> decode(byte[] bytes, int version) throws Exception {
            if (version != 1) throw new IllegalArgumentException("long map version");
            java.io.DataInputStream in = new java.io.DataInputStream(new java.io.ByteArrayInputStream(bytes));
            int count = readCount(in, bytes.length, 2);
            Map<String, Long> result = new java.util.LinkedHashMap<>();
            for (int i = 0; i < count; i++) {
                String key = readMapKey(readFrame(in));
                Long item = com.oem.globaldata.client.Codecs.LONG.decode(readFrame(in), 1);
                if (result.putIfAbsent(key, item) != null)
                    throw new IllegalArgumentException("duplicate map key");
            }
            requireEnd(in);
            return result;
        }
    };

    /** Long Set 的独立 v1 Codec，元素按无符号字节序排序但不承诺迭代顺序。 */
    public static final ValueCodec<Set<Long>> LONG_SET = new ValueCodec<Set<Long>>() {
        public String schemaId() { return "com.oem.device.long-set"; }
        public int schemaVersion() { return 1; }
        public String encoding() { return "length-prefixed-be-v1"; }
        public byte[] encode(Set<Long> value) throws Exception {
            List<byte[]> frames = new ArrayList<>(value.size());
            for (Long item : value) frames.add(com.oem.globaldata.client.Codecs.LONG.encode(java.util.Objects.requireNonNull(item, "set item")));
            frames.sort(DeviceCollections::compareUnsigned);
            java.io.ByteArrayOutputStream buffer = new java.io.ByteArrayOutputStream();
            java.io.DataOutputStream out = new java.io.DataOutputStream(buffer);
            out.writeInt(frames.size());
            for (byte[] frame : frames) writeFrame(out, frame);
            return buffer.toByteArray();
        }
        public Set<Long> decode(byte[] bytes, int version) throws Exception {
            if (version != 1) throw new IllegalArgumentException("long set version");
            java.io.DataInputStream in = new java.io.DataInputStream(new java.io.ByteArrayInputStream(bytes));
            int count = readCount(in, bytes.length, 1);
            Set<Long> result = new LinkedHashSet<>();
            for (int i = 0; i < count; i++) {
                if (!result.add(com.oem.globaldata.client.Codecs.LONG.decode(readFrame(in), 1)))
                    throw new IllegalArgumentException("duplicate set item");
            }
            requireEnd(in);
            return result;
        }
    };

    /** Float 列表的独立 v1 Codec，保留元素原有次序。 */
    public static final ValueCodec<List<Float>> FLOAT_LIST = new ValueCodec<List<Float>>() {
        public String schemaId() { return "com.oem.device.float-list"; }
        public int schemaVersion() { return 1; }
        public String encoding() { return "length-prefixed-be-v1"; }
        public byte[] encode(List<Float> value) throws Exception {
            java.io.ByteArrayOutputStream buffer = new java.io.ByteArrayOutputStream();
            java.io.DataOutputStream out = new java.io.DataOutputStream(buffer);
            out.writeInt(value.size());
            for (Float item : value) writeFrame(out, com.oem.globaldata.client.Codecs.FLOAT.encode(java.util.Objects.requireNonNull(item, "list item")));
            return buffer.toByteArray();
        }
        public List<Float> decode(byte[] bytes, int version) throws Exception {
            if (version != 1) throw new IllegalArgumentException("float list version");
            java.io.DataInputStream in = new java.io.DataInputStream(new java.io.ByteArrayInputStream(bytes));
            int count = readCount(in, bytes.length, 1);
            List<Float> result = new ArrayList<>(count);
            for (int i = 0; i < count; i++) result.add(com.oem.globaldata.client.Codecs.FLOAT.decode(readFrame(in), 1));
            requireEnd(in);
            return result;
        }
    };

    /** String 键、Float 值映射的独立 v1 Codec，按键排序后写入。 */
    public static final ValueCodec<Map<String, Float>> STRING_FLOAT_MAP =
            new ValueCodec<Map<String, Float>>() {
        public String schemaId() { return "com.oem.device.string-float-map"; }
        public int schemaVersion() { return 1; }
        public String encoding() { return "length-prefixed-be-v1"; }
        public byte[] encode(Map<String, Float> value) throws Exception {
            java.io.ByteArrayOutputStream buffer = new java.io.ByteArrayOutputStream();
            java.io.DataOutputStream out = new java.io.DataOutputStream(buffer);
            List<String> keys = new ArrayList<>(value.keySet());
            for (String key : keys) java.util.Objects.requireNonNull(key, "map key");
            Collections.sort(keys);
            out.writeInt(keys.size());
            for (String key : keys) {
                writeFrame(out, key.getBytes(StandardCharsets.UTF_8));
                writeFrame(out, com.oem.globaldata.client.Codecs.FLOAT.encode(java.util.Objects.requireNonNull(value.get(key), "map value")));
            }
            return buffer.toByteArray();
        }
        public Map<String, Float> decode(byte[] bytes, int version) throws Exception {
            if (version != 1) throw new IllegalArgumentException("float map version");
            java.io.DataInputStream in = new java.io.DataInputStream(new java.io.ByteArrayInputStream(bytes));
            int count = readCount(in, bytes.length, 2);
            Map<String, Float> result = new java.util.LinkedHashMap<>();
            for (int i = 0; i < count; i++) {
                String key = readMapKey(readFrame(in));
                Float item = com.oem.globaldata.client.Codecs.FLOAT.decode(readFrame(in), 1);
                if (result.putIfAbsent(key, item) != null)
                    throw new IllegalArgumentException("duplicate map key");
            }
            requireEnd(in);
            return result;
        }
    };

    /** Float Set 的独立 v1 Codec，元素按无符号字节序排序但不承诺迭代顺序。 */
    public static final ValueCodec<Set<Float>> FLOAT_SET = new ValueCodec<Set<Float>>() {
        public String schemaId() { return "com.oem.device.float-set"; }
        public int schemaVersion() { return 1; }
        public String encoding() { return "length-prefixed-be-v1"; }
        public byte[] encode(Set<Float> value) throws Exception {
            List<byte[]> frames = new ArrayList<>(value.size());
            for (Float item : value) frames.add(com.oem.globaldata.client.Codecs.FLOAT.encode(java.util.Objects.requireNonNull(item, "set item")));
            frames.sort(DeviceCollections::compareUnsigned);
            java.io.ByteArrayOutputStream buffer = new java.io.ByteArrayOutputStream();
            java.io.DataOutputStream out = new java.io.DataOutputStream(buffer);
            out.writeInt(frames.size());
            for (byte[] frame : frames) writeFrame(out, frame);
            return buffer.toByteArray();
        }
        public Set<Float> decode(byte[] bytes, int version) throws Exception {
            if (version != 1) throw new IllegalArgumentException("float set version");
            java.io.DataInputStream in = new java.io.DataInputStream(new java.io.ByteArrayInputStream(bytes));
            int count = readCount(in, bytes.length, 1);
            Set<Float> result = new LinkedHashSet<>();
            for (int i = 0; i < count; i++) {
                if (!result.add(com.oem.globaldata.client.Codecs.FLOAT.decode(readFrame(in), 1)))
                    throw new IllegalArgumentException("duplicate set item");
            }
            requireEnd(in);
            return result;
        }
    };

    /** Double 列表的独立 v1 Codec，保留元素原有次序。 */
    public static final ValueCodec<List<Double>> DOUBLE_LIST = new ValueCodec<List<Double>>() {
        public String schemaId() { return "com.oem.device.double-list"; }
        public int schemaVersion() { return 1; }
        public String encoding() { return "length-prefixed-be-v1"; }
        public byte[] encode(List<Double> value) throws Exception {
            java.io.ByteArrayOutputStream buffer = new java.io.ByteArrayOutputStream();
            java.io.DataOutputStream out = new java.io.DataOutputStream(buffer);
            out.writeInt(value.size());
            for (Double item : value) writeFrame(out, com.oem.globaldata.client.Codecs.DOUBLE.encode(java.util.Objects.requireNonNull(item, "list item")));
            return buffer.toByteArray();
        }
        public List<Double> decode(byte[] bytes, int version) throws Exception {
            if (version != 1) throw new IllegalArgumentException("double list version");
            java.io.DataInputStream in = new java.io.DataInputStream(new java.io.ByteArrayInputStream(bytes));
            int count = readCount(in, bytes.length, 1);
            List<Double> result = new ArrayList<>(count);
            for (int i = 0; i < count; i++) result.add(com.oem.globaldata.client.Codecs.DOUBLE.decode(readFrame(in), 1));
            requireEnd(in);
            return result;
        }
    };

    /** String 键、Double 值映射的独立 v1 Codec，按键排序后写入。 */
    public static final ValueCodec<Map<String, Double>> STRING_DOUBLE_MAP =
            new ValueCodec<Map<String, Double>>() {
        public String schemaId() { return "com.oem.device.string-double-map"; }
        public int schemaVersion() { return 1; }
        public String encoding() { return "length-prefixed-be-v1"; }
        public byte[] encode(Map<String, Double> value) throws Exception {
            java.io.ByteArrayOutputStream buffer = new java.io.ByteArrayOutputStream();
            java.io.DataOutputStream out = new java.io.DataOutputStream(buffer);
            List<String> keys = new ArrayList<>(value.keySet());
            for (String key : keys) java.util.Objects.requireNonNull(key, "map key");
            Collections.sort(keys);
            out.writeInt(keys.size());
            for (String key : keys) {
                writeFrame(out, key.getBytes(StandardCharsets.UTF_8));
                writeFrame(out, com.oem.globaldata.client.Codecs.DOUBLE.encode(java.util.Objects.requireNonNull(value.get(key), "map value")));
            }
            return buffer.toByteArray();
        }
        public Map<String, Double> decode(byte[] bytes, int version) throws Exception {
            if (version != 1) throw new IllegalArgumentException("double map version");
            java.io.DataInputStream in = new java.io.DataInputStream(new java.io.ByteArrayInputStream(bytes));
            int count = readCount(in, bytes.length, 2);
            Map<String, Double> result = new java.util.LinkedHashMap<>();
            for (int i = 0; i < count; i++) {
                String key = readMapKey(readFrame(in));
                Double item = com.oem.globaldata.client.Codecs.DOUBLE.decode(readFrame(in), 1);
                if (result.putIfAbsent(key, item) != null)
                    throw new IllegalArgumentException("duplicate map key");
            }
            requireEnd(in);
            return result;
        }
    };

    /** Double Set 的独立 v1 Codec，元素按无符号字节序排序但不承诺迭代顺序。 */
    public static final ValueCodec<Set<Double>> DOUBLE_SET = new ValueCodec<Set<Double>>() {
        public String schemaId() { return "com.oem.device.double-set"; }
        public int schemaVersion() { return 1; }
        public String encoding() { return "length-prefixed-be-v1"; }
        public byte[] encode(Set<Double> value) throws Exception {
            List<byte[]> frames = new ArrayList<>(value.size());
            for (Double item : value) frames.add(com.oem.globaldata.client.Codecs.DOUBLE.encode(java.util.Objects.requireNonNull(item, "set item")));
            frames.sort(DeviceCollections::compareUnsigned);
            java.io.ByteArrayOutputStream buffer = new java.io.ByteArrayOutputStream();
            java.io.DataOutputStream out = new java.io.DataOutputStream(buffer);
            out.writeInt(frames.size());
            for (byte[] frame : frames) writeFrame(out, frame);
            return buffer.toByteArray();
        }
        public Set<Double> decode(byte[] bytes, int version) throws Exception {
            if (version != 1) throw new IllegalArgumentException("double set version");
            java.io.DataInputStream in = new java.io.DataInputStream(new java.io.ByteArrayInputStream(bytes));
            int count = readCount(in, bytes.length, 1);
            Set<Double> result = new LinkedHashSet<>();
            for (int i = 0; i < count; i++) {
                if (!result.add(com.oem.globaldata.client.Codecs.DOUBLE.decode(readFrame(in), 1)))
                    throw new IllegalArgumentException("duplicate set item");
            }
            requireEnd(in);
            return result;
        }
    };

    /** Boolean 列表的独立 v1 Codec，保留元素原有次序。 */
    public static final ValueCodec<List<Boolean>> BOOLEAN_LIST = new ValueCodec<List<Boolean>>() {
        public String schemaId() { return "com.oem.device.boolean-list"; }
        public int schemaVersion() { return 1; }
        public String encoding() { return "length-prefixed-be-v1"; }
        public byte[] encode(List<Boolean> value) throws Exception {
            java.io.ByteArrayOutputStream buffer = new java.io.ByteArrayOutputStream();
            java.io.DataOutputStream out = new java.io.DataOutputStream(buffer);
            out.writeInt(value.size());
            for (Boolean item : value) writeFrame(out, com.oem.globaldata.client.Codecs.BOOLEAN.encode(java.util.Objects.requireNonNull(item, "list item")));
            return buffer.toByteArray();
        }
        public List<Boolean> decode(byte[] bytes, int version) throws Exception {
            if (version != 1) throw new IllegalArgumentException("boolean list version");
            java.io.DataInputStream in = new java.io.DataInputStream(new java.io.ByteArrayInputStream(bytes));
            int count = readCount(in, bytes.length, 1);
            List<Boolean> result = new ArrayList<>(count);
            for (int i = 0; i < count; i++) result.add(com.oem.globaldata.client.Codecs.BOOLEAN.decode(readFrame(in), 1));
            requireEnd(in);
            return result;
        }
    };

    /** String 键、Boolean 值映射的独立 v1 Codec，按键排序后写入。 */
    public static final ValueCodec<Map<String, Boolean>> STRING_BOOLEAN_MAP =
            new ValueCodec<Map<String, Boolean>>() {
        public String schemaId() { return "com.oem.device.string-boolean-map"; }
        public int schemaVersion() { return 1; }
        public String encoding() { return "length-prefixed-be-v1"; }
        public byte[] encode(Map<String, Boolean> value) throws Exception {
            java.io.ByteArrayOutputStream buffer = new java.io.ByteArrayOutputStream();
            java.io.DataOutputStream out = new java.io.DataOutputStream(buffer);
            List<String> keys = new ArrayList<>(value.keySet());
            for (String key : keys) java.util.Objects.requireNonNull(key, "map key");
            Collections.sort(keys);
            out.writeInt(keys.size());
            for (String key : keys) {
                writeFrame(out, key.getBytes(StandardCharsets.UTF_8));
                writeFrame(out, com.oem.globaldata.client.Codecs.BOOLEAN.encode(java.util.Objects.requireNonNull(value.get(key), "map value")));
            }
            return buffer.toByteArray();
        }
        public Map<String, Boolean> decode(byte[] bytes, int version) throws Exception {
            if (version != 1) throw new IllegalArgumentException("boolean map version");
            java.io.DataInputStream in = new java.io.DataInputStream(new java.io.ByteArrayInputStream(bytes));
            int count = readCount(in, bytes.length, 2);
            Map<String, Boolean> result = new java.util.LinkedHashMap<>();
            for (int i = 0; i < count; i++) {
                String key = readMapKey(readFrame(in));
                Boolean item = com.oem.globaldata.client.Codecs.BOOLEAN.decode(readFrame(in), 1);
                if (result.putIfAbsent(key, item) != null)
                    throw new IllegalArgumentException("duplicate map key");
            }
            requireEnd(in);
            return result;
        }
    };

    /** Boolean Set 的独立 v1 Codec，元素按无符号字节序排序但不承诺迭代顺序。 */
    public static final ValueCodec<Set<Boolean>> BOOLEAN_SET = new ValueCodec<Set<Boolean>>() {
        public String schemaId() { return "com.oem.device.boolean-set"; }
        public int schemaVersion() { return 1; }
        public String encoding() { return "length-prefixed-be-v1"; }
        public byte[] encode(Set<Boolean> value) throws Exception {
            List<byte[]> frames = new ArrayList<>(value.size());
            for (Boolean item : value) frames.add(com.oem.globaldata.client.Codecs.BOOLEAN.encode(java.util.Objects.requireNonNull(item, "set item")));
            frames.sort(DeviceCollections::compareUnsigned);
            java.io.ByteArrayOutputStream buffer = new java.io.ByteArrayOutputStream();
            java.io.DataOutputStream out = new java.io.DataOutputStream(buffer);
            out.writeInt(frames.size());
            for (byte[] frame : frames) writeFrame(out, frame);
            return buffer.toByteArray();
        }
        public Set<Boolean> decode(byte[] bytes, int version) throws Exception {
            if (version != 1) throw new IllegalArgumentException("boolean set version");
            java.io.DataInputStream in = new java.io.DataInputStream(new java.io.ByteArrayInputStream(bytes));
            int count = readCount(in, bytes.length, 1);
            Set<Boolean> result = new LinkedHashSet<>();
            for (int i = 0; i < count; i++) {
                if (!result.add(com.oem.globaldata.client.Codecs.BOOLEAN.decode(readFrame(in), 1)))
                    throw new IllegalArgumentException("duplicate set item");
            }
            requireEnd(in);
            return result;
        }
    };
}
