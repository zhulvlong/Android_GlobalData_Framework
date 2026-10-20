package com.oem.globaldata.client;

import android.util.Log;

/** GlobalData diagnostic logging adapted from the supplied TLog API. */
public final class TLog {
    private static final String TAG = "GlobalData";

    private TLog() {}

    private static String message(String component, String... parts) {
        StringBuilder text = new StringBuilder("[").append(component).append("]");
        for (String part : parts) text.append(' ').append(part);
        return text.toString();
    }

    /** Detailed logs are enabled with: adb shell setprop log.tag.GlobalData DEBUG. */
    public static void d(String component, String... parts) {
        try {
            if (Log.isLoggable(TAG, Log.DEBUG)) Log.d(TAG, message(component, parts));
        } catch (RuntimeException ignored) { /* android.jar host test stubs cannot write logcat. */ }
    }

    public static void i(String component, String... parts) {
        try {
            if (Log.isLoggable(TAG, Log.DEBUG)) Log.i(TAG, message(component, parts));
        } catch (RuntimeException ignored) { /* Logging must not affect an SDK call. */ }
    }

    public static void w(String component, String... parts) {
        try { Log.w(TAG, message(component, parts)); }
        catch (RuntimeException ignored) { /* Logging must not affect an SDK call. */ }
    }

    public static void e(String component, String... parts) {
        try { Log.e(TAG, message(component, parts)); }
        catch (RuntimeException ignored) { /* Logging must not affect an SDK call. */ }
    }

    /** Address fields only; never stringify a DataKey's codec or business value. */
    public static String address(DataKey<?> key) {
        if (key == null) return "address=null";
        return "store=" + key.store + " scope=" + key.scope + " owner=" + key.ownerPackage
                + " ns=" + key.namespace + " key=" + key.key;
    }

    public static String address(EventTopic topic) {
        if (topic == null) return "address=null";
        return "scope=" + topic.scope + " owner=" + topic.ownerPackage + " topic=" + topic.topic;
    }
}
