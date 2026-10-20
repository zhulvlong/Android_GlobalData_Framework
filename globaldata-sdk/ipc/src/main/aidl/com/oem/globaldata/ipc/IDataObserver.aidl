package com.oem.globaldata.ipc;
import android.os.Bundle;
/** 数据变化的异步 Binder 回调；通知只是提示，客户端需重新读取当前值。 */
oneway interface IDataObserver {
    /** Key 成功写入或删除后收到事件地址、操作码、revision 和 epoch。 */
    void onDataChanged(in Bundle event);
    /** 通知队列溢出时要求客户端重新同步；serviceEpoch 标识当前服务实例。 */
    void onResyncRequired(String serviceEpoch);
}
