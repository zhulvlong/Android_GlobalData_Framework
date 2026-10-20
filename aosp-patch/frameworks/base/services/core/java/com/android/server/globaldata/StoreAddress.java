package com.android.server.globaldata;

import android.os.Bundle;
import com.oem.globaldata.ipc.Wire;
import java.util.Objects;

final class StoreAddress {
    final int store, scope, userId;
    final String owner, namespace, key;
    StoreAddress(int store, int scope, int userId, String owner, String namespace, String key) {
        this.store = store; this.scope = scope; this.userId = userId;
        this.owner = owner; this.namespace = namespace; this.key = key;
    }
    static StoreAddress parse(Bundle b, int userId) {
        if (b == null) throw new IllegalArgumentException("missing address");
        b.setClassLoader(StoreAddress.class.getClassLoader());
        int store = b.getInt(Wire.STORE), scope = b.getInt(Wire.SCOPE);
        String owner = b.getString(Wire.OWNER), ns = b.getString(Wire.NAMESPACE), key = b.getString(Wire.KEY);
        if (store < Wire.MEMORY || store > Wire.DATABASE || scope < Wire.DEVICE_DE || scope > Wire.USER_CE
                || !name(owner, 180) || !name(ns, 180) || !name(key, 180))
            throw new IllegalArgumentException("invalid address");
        return new StoreAddress(store, scope, scope == Wire.DEVICE_DE ? 0 : userId, owner, ns, key);
    }
    static boolean name(String value, int max) {
        return value != null && !value.isEmpty() && value.length() <= max
                && value.matches("[A-Za-z0-9_.\\-/]+") && !value.contains("..")
                && !value.startsWith("/") && !value.endsWith("/");
    }
    String namespaceId() { return scope + "|" + userId + "|" + owner + "|" + namespace; }
    String fullId() { return namespaceId() + "|" + key; }
    @Override public boolean equals(Object obj) {
        if (!(obj instanceof StoreAddress)) return false;
        StoreAddress o = (StoreAddress) obj;
        return store == o.store && scope == o.scope && userId == o.userId
                && owner.equals(o.owner) && namespace.equals(o.namespace) && key.equals(o.key);
    }
    @Override public int hashCode() { return Objects.hash(store, scope, userId, owner, namespace, key); }
}
