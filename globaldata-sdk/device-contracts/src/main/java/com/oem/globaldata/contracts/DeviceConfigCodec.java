package com.oem.globaldata.contracts;

import com.oem.globaldata.client.ValueCodec;
import java.nio.charset.StandardCharsets;
import org.json.JSONObject;

/**
 * DeviceConfig 的 JSON/UTF-8 编解码约定。服务端只保存字节和 schema 信息，
 * 对字段的解释由使用同一 Contracts AAR 的客户端负责。
 */
public final class DeviceConfigCodec implements ValueCodec<DeviceConfig> {
    /** 无状态 Codec 的共享实例，所有业务 Key 使用相同格式约定。 */
    public static final DeviceConfigCodec INSTANCE = new DeviceConfigCodec();

    /** 禁止外部创建无意义的重复实例。 */
    private DeviceConfigCodec() {}

    /** {@inheritDoc} 固定类型标识，避免把其他 JSON 对象按设备配置读取。 */
    @Override public String schemaId() { return "com.oem.device.config"; }

    /** {@inheritDoc} 当前写入格式为 v2，包含 group 字段。 */
    @Override public int schemaVersion() { return 2; }

    /** {@inheritDoc} 字节与字符串的转换必须使用 UTF-8。 */
    @Override public String encoding() { return "json-utf8"; }

    /**
     * 按 v2 字段约定编码，供 SDK 写入任一种 Store。
     *
     * @param value 要保存的配置
     * @return JSON 的 UTF-8 字节
     * @throws Exception JSON 构造失败时由 SDK 向调用方报告
     */
    @Override public byte[] encode(DeviceConfig value) throws Exception {
        JSONObject json = new JSONObject();
        // 字段名是 App1/App2 的持久化契约；更名需要迁移旧数据。
        json.put("deviceId", value.deviceId);
        json.put("deviceName", value.deviceName);
        json.put("enabled", value.enabled);
        json.put("group", value.group);
        // 统一字符集，使不同进程/设备的字节内容可互相解码。
        return json.toString().getBytes(StandardCharsets.UTF_8);
    }

    /**
     * 读取 v1/v2 配置；v1 没有 group 时补空字符串，保证旧数据仍可读。
     *
     * @param bytes 服务端返回的 JSON 字节
     * @param version 随存储值保存的 schema 版本
     * @return 解析后的配置对象
     * @throws Exception 版本不支持或 JSON 字段缺失、类型错误时抛出
     */
    @Override public DeviceConfig decode(byte[] bytes, int version) throws Exception {
        // 拒绝未知未来版本，避免在不理解新增语义时静默按旧格式读取。
        if (version < 1 || version > 2) throw new IllegalArgumentException("unsupported schema " + version);
        JSONObject json = new JSONObject(new String(bytes, StandardCharsets.UTF_8));
        // optString 让 v1 中缺失的 group 有确定的默认值。
        return new DeviceConfig(json.getInt("deviceId"), json.getString("deviceName"),
                json.getBoolean("enabled"), json.optString("group", ""));
    }
}
