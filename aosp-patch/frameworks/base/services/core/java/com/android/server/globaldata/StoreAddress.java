package com.android.server.globaldata;

import android.os.Bundle;
import com.oem.globaldata.ipc.Wire;
import java.util.Objects;

/**
 * 单条数据的完整服务端地址。store 选择后端，scope/userId 选择 DE/CE 用户目录，
 * owner/namespace/key 定位业务记录；ACL 授权粒度是 namespace 而非单个 Key。
 * 服务端统一构造地址，避免各后端各自解释用户、owner 或路径规则而产生越权差异。
 */
final class StoreAddress {
    /** store、scope 为 Wire 枚举；DEVICE_DE 地址的 userId 统一归一化为 0。 */
    final int store, scope, userId;
    /** owner 为包名，namespace 为授权分组，key 为组内记录名。 */
    final String owner, namespace, key;
    StoreAddress(int store, int scope, int userId, String owner, String namespace, String key) {
        this.store = store; this.scope = scope; this.userId = userId;
        this.owner = owner; this.namespace = namespace; this.key = key;
    }
    /**
     * 从 Binder Bundle 解析地址，userId 由真实调用 UID 推导，不信任请求里的用户信息；
     * 否则 App 可以伪造另一个 Android 用户的数据地址。
     */
    static StoreAddress parse(Bundle b, int userId) {
        if (b == null) throw new IllegalArgumentException("missing address");
        // 1. 用服务端可见的类加载器解析 Bundle，避免依赖 App 私有业务类。
        b.setClassLoader(StoreAddress.class.getClassLoader());
        // 2. 只读取线缆协议允许的字段，并统一验证后端、scope 与名称。
        int store = b.getInt(Wire.STORE), scope = b.getInt(Wire.SCOPE);
        String owner = b.getString(Wire.OWNER), ns = b.getString(Wire.NAMESPACE), key = b.getString(Wire.KEY);
        if (store < Wire.MEMORY || store > Wire.DATABASE || scope < Wire.DEVICE_DE || scope > Wire.USER_CE
                || !name(owner, 180) || !name(ns, 180) || !name(key, 180))
            throw new IllegalArgumentException("invalid address");
        // 3. 设备级地址归 user 0；用户级地址始终取 Binder UID 推导的 userId。
        return new StoreAddress(store, scope, scope == Wire.DEVICE_DE ? 0 : userId, owner, ns, key);
    }
    /**
     * 统一限制长度和可用字符，并拒绝“..”等路径穿越形态；
     * 文件后端、ACL 和日志都能复用同一套安全地址规则。
     */
    static boolean name(String value, int max) {
        // 允许有限的路径样式分隔符，但不允许上级目录或绝对路径形态。
        return value != null && !value.isEmpty() && value.length() <= max
                && value.matches("[A-Za-z0-9_.\\-/]+") && !value.contains("..")
                && !value.startsWith("/") && !value.endsWith("/");
    }
    /** 后端的 namespace 标识，不含单条 key；高水位和文件快照按此分组。 */
    String namespaceId() { return scope + "|" + userId + "|" + owner + "|" + namespace; }
    /** 含 key 的完整标识，用于区分单条数据。 */
    String fullId() { return namespaceId() + "|" + key; }
    @Override public boolean equals(Object obj) {
        if (!(obj instanceof StoreAddress)) return false;
        StoreAddress o = (StoreAddress) obj;
        return store == o.store && scope == o.scope && userId == o.userId
                && owner.equals(o.owner) && namespace.equals(o.namespace) && key.equals(o.key);
    }
    @Override public int hashCode() { return Objects.hash(store, scope, userId, owner, namespace, key); }
}
