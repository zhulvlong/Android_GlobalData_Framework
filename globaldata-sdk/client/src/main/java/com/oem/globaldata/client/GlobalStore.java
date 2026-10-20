package com.oem.globaldata.client;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;

/**
 * 预先绑定 Store、Scope、owner 和 namespace 的便利入口。
 * 每个方法仍通过同一个类型化 manager 工作，不会另建系统服务或独立缓存。
 */
public final class GlobalStore {
    /** 提供实际读写、订阅和 Binder 生命周期。 */
    private final GlobalDataManager manager;
    /** 此门面固定的数据后端与设备/用户作用域。 */
    private final int store, scope;
    /** 此门面固定的资源管理者和授权分组。 */
    private final String owner, namespace;
    /** 由 manager.memory/file/database 创建，统一保存地址前缀。 */
    GlobalStore(GlobalDataManager manager, int store, int scope, String owner, String namespace) {
        this.manager = manager; this.store = store; this.scope = scope;
        this.owner = owner; this.namespace = namespace;
    }
    /**
     * 在固定前缀下定义一条有类型的数据地址。
     * @param name namespace 内的 Key 名
     * @param codec App 侧编解码约定
     * @return 可交给 manager 各类方法使用的 DataKey
     */
    public <T> DataKey<T> key(String name, ValueCodec<T> codec) {
        return DataKey.of(store, scope, owner, namespace, name, codec);
    }
    /**
     * 用 Codecs.INT 异步写入整数；可从任意线程调用，不应在主线程阻塞等待结果。
     * @param name 当前 namespace 内的 Key 名
     * @param value 要写入的整数
     * @return 成功时含 revision 和 epoch 的 Future；失败时 Future 异常完成
     */
    public CompletableFuture<WriteResult> putInt(String name, int value) {
        return manager.put(key(name, Codecs.INT), value);
    }
    /**
     * 用 Codecs.INT 异步读取整数。
     * @param name 当前 namespace 内的 Key 名
     * @return int 快照 Future；不存在时 present=false
     */
    public CompletableFuture<DataSnapshot<Integer>> getInt(String name) {
        return manager.read(key(name, Codecs.INT));
    }
    /**
     * 用 Codecs.LONG 异步写入 64 位整数；适合累计字节数等超出 int 的值。
     * @param name 当前 namespace 内的 Key 名
     * @param value 要写入的 long
     * @return 完成后含 revision 和 epoch 的 Future；可从主线程发起但不要阻塞等待
     */
    public CompletableFuture<WriteResult> putLong(String name, long value) {
        return manager.put(key(name, Codecs.LONG), value);
    }
    /**
     * 用相同 Codec 异步读取 64 位整数。
     * @param name 写入时使用的 Key 名
     * @return long 快照 Future；不存在时 present=false
     */
    public CompletableFuture<DataSnapshot<Long>> getLong(String name) {
        return manager.read(key(name, Codecs.LONG));
    }
    /**
     * 用 Codecs.FLOAT 异步写入 32 位浮点数，包含 IEEE 754 特殊值。
     * @param name 当前 namespace 内的 Key 名
     * @param value 要写入的 float
     * @return 异步写入结果；界面更新需由调用方切到主线程
     */
    public CompletableFuture<WriteResult> putFloat(String name, float value) {
        return manager.put(key(name, Codecs.FLOAT), value);
    }
    /**
     * 用相同 Codec 异步读取 32 位浮点数。
     * @param name 写入时使用的 Key 名
     * @return float 快照 Future；不存在时 present=false
     */
    public CompletableFuture<DataSnapshot<Float>> getFloat(String name) {
        return manager.read(key(name, Codecs.FLOAT));
    }
    /**
     * 用 Codecs.DOUBLE 异步写入 64 位浮点数，保留 -0.0 等位表示。
     * @param name 当前 namespace 内的 Key 名
     * @param value 要写入的 double
     * @return 异步写入结果；失败时 Future 异常完成
     */
    public CompletableFuture<WriteResult> putDouble(String name, double value) {
        return manager.put(key(name, Codecs.DOUBLE), value);
    }
    /**
     * 用相同 Codec 异步读取 64 位浮点数。
     * @param name 写入时使用的 Key 名
     * @return double 快照 Future；不存在时 present=false
     */
    public CompletableFuture<DataSnapshot<Double>> getDouble(String name) {
        return manager.read(key(name, Codecs.DOUBLE));
    }
    /**
     * 用 Codecs.STRING 异步写入 UTF-8 字符串。
     * @param name 当前 namespace 内的 Key 名
     * @param value 要写入的字符串
     * @return 异步写入结果 Future
     */
    public CompletableFuture<WriteResult> putString(String name, String value) {
        return manager.put(key(name, Codecs.STRING), value);
    }
    /**
     * 用 Codecs.STRING 异步读取字符串。
     * @param name 当前 namespace 内的 Key 名
     * @return 解码后的字符串快照 Future
     */
    public CompletableFuture<DataSnapshot<String>> getString(String name) {
        return manager.read(key(name, Codecs.STRING));
    }
    /**
     * 用业务 Codec 写入对象，POJO 仍只在 App 侧编码。
     * @param name Key 名
     * @param value 非 null 业务值
     * @param codec 与读端约定一致的 Codec
     * @return 异步写入结果
     */
    public <T> CompletableFuture<WriteResult> putObject(String name, T value, ValueCodec<T> codec) {
        return manager.put(key(name, codec), value);
    }
    /**
     * 按业务 Codec 异步读取对象。
     * @param name Key 名
     * @param codec 与存储 schema 兼容的 Codec
     * @return 存在或不存在的类型化快照
     */
    public <T> CompletableFuture<DataSnapshot<T>> getObject(String name, ValueCodec<T> codec) {
        return manager.read(key(name, codec));
    }
    /**
     * 先订阅指定 Key，再读取一次当前值，后续通知也会触发重新读取。
     * @param name Key 名
     * @param codec 值的类型约定
     * @param callback 回调执行器
     * @param listener 当前快照与错误的接收方
     * @return 需在生命周期结束时关闭的订阅句柄
     */
    public <T> CompletableFuture<Subscription> observe(String name, ValueCodec<T> codec,
                                                        Executor callback, DataListener<T> listener) {
        return manager.observeAndRead(key(name, codec), callback, listener);
    }
}
