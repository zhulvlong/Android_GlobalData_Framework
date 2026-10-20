package android.globaldata;

import android.util.Log;

/** Framework-side copy of the adapted TLog API; deliberately not a public SDK API. */
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
        catch (RuntimeException ignored) { /* Logging must not affect Binder lookup. */ }
    }

    static void i(String component, String... parts) {
        try { if (Log.isLoggable(TAG, Log.DEBUG)) Log.i(TAG, message(component, parts)); }
        catch (RuntimeException ignored) { /* Logging must not affect Binder lookup. */ }
    }

    static void w(String component, String... parts) {
        try { Log.w(TAG, message(component, parts)); }
        catch (RuntimeException ignored) { /* Logging must not affect Binder lookup. */ }
    }

    static void e(String component, String... parts) {
        try { Log.e(TAG, message(component, parts)); }
        catch (RuntimeException ignored) { /* Logging must not affect Binder lookup. */ }
    }
}
