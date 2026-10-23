package android.globaldata;

import android.annotation.Nullable;
import android.os.IBinder;
import android.os.ServiceManager;

/**
 * OEM 对外提供的 Binder 入口。客户端 SDK 经此取得 GlobalData 传输 Binder，
 * 随后由 system_server 对每次调用独立执行身份、作用域和 ACL 校验。
 * 返回 Binder 本身不授予读写权限。
 * 这样普通 App 可以使用公开 API 接入，不必反射隐藏的 ServiceManager，
 * 也不需要把业务 SDK 放进 framework；权限边界仍集中在系统服务。
 */
public final class GlobalDataBridge {
    private static final String TAG = "GlobalDataBridge";

    /** 与系统服务 publishBinderService 使用的名称保持一致。 */
    private static final String SERVICE = "globaldata";
    private GlobalDataBridge() {}

    /**
     * 每次查找当前服务实例，不长期缓存 Binder：服务重启后旧 Binder 会失效，
     * SDK 才能重新握手并恢复订阅。未发布时返回 null，由 SDK 决定重试。
     */
    @Nullable public static IBinder getTransportBinder() {
        TLog.d(TAG, "lookup service=" + SERVICE);
        // 只做服务发现；调用权限交给服务端逐操作校验，Bridge 不缓存旧 Binder。
        IBinder binder = ServiceManager.getService(SERVICE);
        if (binder == null) TLog.w(TAG, "service unavailable");
        else TLog.d(TAG, "binder acquired alive=" + binder.isBinderAlive());
        return binder;
    }
}
