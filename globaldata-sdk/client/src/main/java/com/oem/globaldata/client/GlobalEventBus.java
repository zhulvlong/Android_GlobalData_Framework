package com.oem.globaldata.client;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.function.Consumer;

/** 无载荷事件的客户端入口，所有操作仍复用同一个 GlobalDataManager 的 Binder。 */
public final class GlobalEventBus {
    private static final String TAG = "GlobalEventBus";

    /** 持有 manager，以共享其连接、执行器和关闭生命周期。 */
    private final GlobalDataManager manager;
    /** 由 manager.events() 创建，业务代码不直接构造。 */
    GlobalEventBus(GlobalDataManager manager) { this.manager = manager; }
    /**
     * 向当时在线且获授权的订阅者发布 topic，不保存也不补发事件。
     * @param topic 已约定的完整事件地址
     * @return Future 成功仅表示服务端接受并排队，不保证每个订阅者已收到
     */
    public CompletableFuture<Void> post(EventTopic topic) {
        TLog.d(TAG, "post " + TLog.address(topic));
        return manager.post(topic);
    }
    /**
     * 订阅完整 topic，回调只返回 topic；需要业务值时应再读 DataKey。
     * @param topic 已约定的事件地址
     * @param callback 执行监听回调的线程池或主线程 Executor
     * @param listener 在线事件到达时的处理函数
     * @return 可关闭的订阅句柄；服务重连后会重订阅，但不补发旧事件
     */
    public CompletableFuture<Subscription> subscribe(EventTopic topic, Executor callback,
                                                      Consumer<EventTopic> listener) {
        TLog.d(TAG, "subscribe " + TLog.address(topic));
        return manager.subscribe(topic, callback, listener);
    }
}
