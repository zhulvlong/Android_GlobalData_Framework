package com.android.server.globaldata;

import android.util.Log;

/** System-service copy of the adapted TLog API; independent of App libraries. */
final class TLog {
    private static final String TAG = "GlobalData";

    private TLog() {}

    private static String message(String component, String... parts) {
        StringBuilder text = new StringBuilder("[").append(component).append("]");
        for (String part : parts) text.append(' ').append(part);
        return text.toString();
    }

    static void d(String component, String... parts) {
        try { if (Log.isLoggable(TAG, Log.DEBUG)) Log.d(TAG, message(component, parts)); }
        catch (RuntimeException ignored) { /* Logging must not affect a service operation. */ }
    }

    static void i(String component, String... parts) {
        try { if (Log.isLoggable(TAG, Log.DEBUG)) Log.i(TAG, message(component, parts)); }
        catch (RuntimeException ignored) { /* Logging must not affect a service operation. */ }
    }

    static void w(String component, String... parts) {
        try { Log.w(TAG, message(component, parts)); }
        catch (RuntimeException ignored) { /* Logging must not affect a service operation. */ }
    }

    static void e(String component, String... parts) {
        try { Log.e(TAG, message(component, parts)); }
        catch (RuntimeException ignored) { /* Logging must not affect a service operation. */ }
    }

    static String address(StoreAddress key) {
        return "store=" + key.store + " scope=" + key.scope + " user=" + key.userId
                + " owner=" + key.owner + " ns=" + key.namespace + " key=" + key.key;
    }

    static String address(AccessController.Resource resource) {
        return "type=" + resource.type + " scope=" + resource.scope + " user=" + resource.userId
                + " owner=" + resource.owner + " name=" + resource.name;
    }
}
