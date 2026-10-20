package com.oem.globaldata.client;

/** 单个 DataKey 的状态监听；回调由调用方提供的 Executor 串行执行。 */
public interface DataListener<T> {
    /**
     * 收到订阅后的初始读取，或后续通知触发的重新读取结果。
     * @param snapshot 当前快照；连续写入可能合并成同一个最终状态
     */
    void onChanged(DataSnapshot<T> snapshot);
    /** 服务死亡或通知队列溢出后提示业务界面重同步；SDK 会尝试恢复并再读。 */
    default void onResyncRequired() {}
    /**
     * 初始/后续读取或恢复订阅失败；默认忽略，建议业务界面覆盖。
     * @param error 失败原因，可用于界面提示或日志
     */
    default void onError(Throwable error) {}
}
