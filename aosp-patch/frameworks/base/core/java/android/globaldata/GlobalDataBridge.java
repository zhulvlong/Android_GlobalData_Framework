package android.globaldata;

import android.annotation.Nullable;
import android.os.IBinder;
import android.os.ServiceManager;

/**
 * OEM public entry point for the versioned GlobalData transport.
 * The returned Binder is unprivileged; every operation is checked in system_server.
 */
public final class GlobalDataBridge {
    private static final String TAG = "GlobalDataBridge";

    private static final String SERVICE = "globaldata";
    private GlobalDataBridge() {}

    /** Returns null while the system service is unavailable. */
    @Nullable public static IBinder getTransportBinder() {
        TLog.d(TAG, "lookup service=" + SERVICE);
        IBinder binder = ServiceManager.getService(SERVICE);
        if (binder == null) TLog.w(TAG, "service unavailable");
        else TLog.d(TAG, "binder acquired alive=" + binder.isBinderAlive());
        return binder;
    }
}
