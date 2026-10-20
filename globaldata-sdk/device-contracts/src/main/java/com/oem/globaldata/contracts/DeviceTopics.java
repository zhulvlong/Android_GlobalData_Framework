package com.oem.globaldata.contracts;

import com.oem.globaldata.client.EventTopic;
import com.oem.globaldata.ipc.Wire;

/**
 * App1/App2 共用的无载荷事件地址表，作用类似 DeviceDataKeys 对数据地址的集中定义。
 * 方法名写明约定的发送方、接收方和事件含义；事件没有 Store，不存储业务数据。
 * 方向是业务约定，实际发布与订阅权限由服务端根据 Binder 身份检查。
 */
public final class DeviceTopics {
    /** 静态事件主题工厂不需要实例。 */
    private DeviceTopics() {}

    /**
     * App1 向 App2 通知“配置已刷新”；App2 收到后如需新配置，应另行读取数据 Key。
     * @param system true 选择平台 DEVICE_DE 主题；false 选择普通 USER_CE 主题
     * @return 双方发布和订阅时共同使用的无载荷事件地址
     */
    public static EventTopic app1ToApp2ConfigRefreshedEvent(boolean system) {
        return EventTopic.of(system ? Wire.DEVICE_DE : Wire.USER_CE,
                DeviceDataKeys.APP1_PACKAGE,
                system ? "sys.device/CONFIG_REFRESHED"
                        : "events.com.oem.globaldata.app1/CONFIG_REFRESHED");
    }

    /**
     * App2 向 App1 请求刷新配置；topic 由 App1 拥有，App2 需取得 PUBLISH 权限。
     * @param system true 选择平台 DEVICE_DE 主题；false 选择普通 USER_CE 主题
     * @return 双方发布和订阅时共同使用的无载荷事件地址
     */
    public static EventTopic app2ToApp1ConfigRefreshRequestEvent(boolean system) {
        return EventTopic.of(system ? Wire.DEVICE_DE : Wire.USER_CE,
                DeviceDataKeys.APP1_PACKAGE,
                system ? "sys.device/REQUEST_CONFIG_REFRESH"
                        : "events.com.oem.globaldata.app1/REQUEST_CONFIG_REFRESH");
    }

    /**
     * 构造早期综合示例使用的通用配置刷新事件地址；当前双向演示使用上面两个方向明确的主题。
     * 发布者和订阅者须采用相同 scope、owner 与主题名。
     *
     * @param system true 用平台 DEVICE_DE/sys.* 主题；false 用普通 USER_CE 主题
     * @return 无载荷刷新主题；收到事件后应另行读取所需数据
     */
    public static EventTopic legacyConfigRefreshEvent(boolean system) {
        // sys.* 主题留给受信任平台身份；普通 App 主题归 App1 授权管理。
        return EventTopic.of(system ? Wire.DEVICE_DE : Wire.USER_CE,
                DeviceDataKeys.APP1_PACKAGE,
                system ? "sys.device/CONFIG_REFRESH"
                        : "events.com.oem.globaldata.app1/CONFIG_REFRESH");

    }
}
