package android.globaldata;

import android.os.IBinder;

/**
 * 仅供 SDK 编译的公开 Bridge 桩。设备运行时应加载定制 framework.jar 中的同名实现；
 * 本桩不能打包进 APK，也不会绕过系统服务对真实 Binder UID 的权限检查。
 */
public final class GlobalDataBridge {
    /** 工具类不创建实例。 */
    private GlobalDataBridge() {}
    /**
     * 获取系统发布的传输 Binder。此桩故意抛错，避免缺少真实系统镜像时误以为服务可用。
     * @return 真实系统实现返回传输 Binder；服务尚未发布时可返回 null
     * @throws UnsupportedOperationException 此编译桩被错误地用于运行时
     */
    public static IBinder getTransportBinder() { throw new UnsupportedOperationException("stub"); }
}
