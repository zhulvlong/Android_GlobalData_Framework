package com.android.server.globaldata;

import android.util.Log;

/**
 * system_server 专用日志适配器；与 App/Bridge 副本独立编译。
 * 只输出地址和状态等定位信息，避免把业务 payload 或签名摘要写入系统日志。
 */
final class TLog {
    /** logcat 的统一 tag；各调用类以 component 作为消息中的二级标识。 */
    private static final String TAG = "GlobalData";

    private TLog() {}

    /** 拼接类名和无业务载荷的诊断元信息。 */
    private static String message(String component, String... parts) {
        StringBuilder text = new StringBuilder("[").append(component).append("]");
        for (String part : parts) text.append(' ').append(part);
        return text.toString();
    }

    /** 详细过程需显式开启 DEBUG，避免服务端高频读写默认产生海量日志。 */
    static void d(String component, String... parts) {
        try { if (Log.isLoggable(TAG, Log.DEBUG)) Log.d(TAG, message(component, parts)); }
        catch (RuntimeException ignored) { /* Logging must not affect a service operation. */ }
    }

    /** 普通进度信息也服从 DEBUG 开关。 */
    static void i(String component, String... parts) {
        try { if (Log.isLoggable(TAG, Log.DEBUG)) Log.i(TAG, message(component, parts)); }
        catch (RuntimeException ignored) { /* Logging must not affect a service operation. */ }
    }

    /** 拒绝、锁定和容量等警告持续输出；日志异常不能改变服务处理结果。 */
    static void w(String component, String... parts) {
        try { Log.w(TAG, message(component, parts)); }
        catch (RuntimeException ignored) { /* Logging must not affect a service operation. */ }
    }

    /** 错误持续输出；日志异常不能改变服务处理结果。 */
    static void e(String component, String... parts) {
        try { Log.e(TAG, message(component, parts)); }
        catch (RuntimeException ignored) { /* Logging must not affect a service operation. */ }
    }

    /** 输出数据地址元信息，不包含实际业务值或编码 payload。 */
    static String address(StoreAddress key) {
        return "store=" + key.store + " scope=" + key.scope + " user=" + key.userId
                + " owner=" + key.owner + " ns=" + key.namespace + " key=" + key.key;
    }

    /** 输出 ACL/事件资源地址元信息，不包含签名摘要。 */
    static String address(AccessController.Resource resource) {
        return "type=" + resource.type + " scope=" + resource.scope + " user=" + resource.userId
                + " owner=" + resource.owner + " name=" + resource.name;
    }
}
