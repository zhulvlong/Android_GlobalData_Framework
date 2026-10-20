package com.oem.globaldata.client;

import android.os.Bundle;
import com.oem.globaldata.ipc.Wire;
import java.util.Objects;

/**
 * 一个有类型的数据地址。两端只有使用相同 Store、Scope、owner、namespace 和 key 才会读到同一值；
 * {@code ownerPackage} 只是地址字段，服务端仍按真实 Binder UID 和包签名验证身份。
 * Codec 仅在客户端运行，不会作为 Java 对象传给 system_server。
 * @param <T> 客户端看到的值类型
 */
public final class DataKey<T> {
    /** 存储后端与用户/设备作用域，两者共同参与地址隔离。 */
    public final int store, scope;
    /** owner 决定授权管理者；namespace 是授权分组；key 是组内单条记录名。 */
    public final String ownerPackage, namespace, key;
    /** 在 App 侧把 T 与字节互转的格式约定。 */
    public final ValueCodec<T> codec;
    /** 统一从工厂创建，避免不完整的地址实例。 */
    private DataKey(int store, int scope, String ownerPackage, String namespace,
                    String key, ValueCodec<T> codec) {
        // 数值与名称是否合法仍由服务端验证；这里只阻止缺少必要字段。
        this.store = store; this.scope = scope; this.ownerPackage = Objects.requireNonNull(ownerPackage);
        this.namespace = Objects.requireNonNull(namespace); this.key = Objects.requireNonNull(key);
        this.codec = Objects.requireNonNull(codec);
    }
    /**
     * 定义一条业务数据的稳定地址，App1 和 App2 应从同一 Contracts AAR 取得它。
     * @param store Wire.MEMORY、FILE 或 DATABASE
     * @param scope Wire.DEVICE_DE、USER_DE 或 USER_CE
     * @param ownerPackage 资源管理者的包名；服务端会核对真实调用身份
     * @param namespace 数据授权分组，同组授权覆盖三个 Store 的 Key
     * @param key 分组内的单条记录名
     * @param codec T 的客户端编码约定
     * @return 不可变的数据地址
     */
    public static <T> DataKey<T> of(int store, int scope, String ownerPackage,
                                     String namespace, String key, ValueCodec<T> codec) {
        return new DataKey<>(store, scope, ownerPackage, namespace, key, codec);
    }
    /**
     * 仅把服务端理解的地址字段装入 Bundle；schema 和 payload 在写入时单独传输。
     * @return 每次调用新建的请求 Bundle，可安全附加 CAS 的 expectedEpoch
     */
    public Bundle wire() {
        Bundle b = new Bundle(); b.putInt(Wire.STORE, store); b.putInt(Wire.SCOPE, scope);
        b.putString(Wire.OWNER, ownerPackage); b.putString(Wire.NAMESPACE, namespace);
        b.putString(Wire.KEY, key); return b;
    }
}
