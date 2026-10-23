package android.globaldata;

import android.util.Log;

/**
 * Framework Bridge 专用日志适配器；与服务端副本独立编译，不作为公开 SDK API。
 * 统一 logcat tag 便于跨进程过滤，消息前缀保留类名以区分调用位置。
 */
final class TLog {
    /** logcat 的统一 tag；调用方类名作为消息中的二级标识。 */
    private static final String TAG = "GlobalData";

    private TLog() {}

    /** 在统一 tag 下保留组件来源，便于沿 Bridge 与服务端日志追踪请求。 */
    private static String message(String component, String... parts) {
        StringBuilder text = new StringBuilder("[").append(component).append("]");
        for (String part : parts) text.append(' ').append(part);
        return text.toString();
    }

    /** 详细日志默认关闭，避免生产设备持续记录大量查询过程；诊断时再按属性开启。 */
    static void d(String component, String... parts) {
        try { if (Log.isLoggable(TAG, Log.DEBUG)) Log.d(TAG, message(component, parts)); }
        catch (RuntimeException ignored) { /* Logging must not affect Binder lookup. */ }
    }

    /** 普通进度日志也服从 DEBUG 开关，避免默认设备产生大量记录。 */
    static void i(String component, String... parts) {
        try { if (Log.isLoggable(TAG, Log.DEBUG)) Log.i(TAG, message(component, parts)); }
        catch (RuntimeException ignored) { /* Logging must not affect Binder lookup. */ }
    }

    /** 服务不可用等警告始终输出；日志系统异常也不能改变 Binder 查找结果。 */
    static void w(String component, String... parts) {
        try { Log.w(TAG, message(component, parts)); }
        catch (RuntimeException ignored) { /* Logging must not affect Binder lookup. */ }
    }

    /** 错误始终输出；日志异常不能影响 Binder 查找。 */
    static void e(String component, String... parts) {
        try { Log.e(TAG, message(component, parts)); }
        catch (RuntimeException ignored) { /* Logging must not affect Binder lookup. */ }
    }
}
