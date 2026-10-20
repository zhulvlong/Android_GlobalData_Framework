package com.oem.globaldata.contracts;

/**
 * App1 与 App2 共用的不可变业务对象示例。字段意义和 JSON 格式由本 Contracts AAR 共同约定。
 * 新增业务字段时可升级该 AAR 和 APK，无须为该字段改动系统服务。
 */
public final class DeviceConfig {
    /** 示例设备的业务编号，不是 Android 设备 ID。 */
    public final int deviceId;
    /** 用户可见的设备名称。 */
    public final String deviceName;
    /** 业务上的启用状态。 */
    public final boolean enabled;
    /** v2 新增的分组；旧版解码器可忽略 JSON 中未知的此字段。 */
    public final String group;

    /**
     * 创建完整配置。构造时固定字段值，便于把同一对象交给异步编码操作。
     *
     * @param deviceId 业务设备编号
     * @param deviceName 设备名称
     * @param enabled 是否启用
     * @param group 分组名称；编码器按传入值写入 JSON
     */
    public DeviceConfig(int deviceId, String deviceName, boolean enabled, String group) {
        this.deviceId = deviceId;
        this.deviceName = deviceName;
        this.enabled = enabled;
        this.group = group;
    }

    /**
     * 生成仅改变启用状态的新对象，保留原对象供调用方继续使用。
     *
     * @param next 新的启用状态
     * @return 其余字段与当前对象相同的新配置
     */
    public DeviceConfig withEnabled(boolean next) {
        return new DeviceConfig(deviceId, deviceName, next, group);
    }
}
