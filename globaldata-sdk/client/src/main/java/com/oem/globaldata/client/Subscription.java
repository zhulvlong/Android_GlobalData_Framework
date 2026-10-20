package com.oem.globaldata.client;

/** 一个数据 Key 或事件 Topic 的订阅句柄，归创建它的 GlobalDataManager 管理。 */
public interface Subscription extends AutoCloseable {
    /** 取消本订阅；界面销毁或改订阅时调用，manager.close 也会清理它。 */
    @Override void close();
}
