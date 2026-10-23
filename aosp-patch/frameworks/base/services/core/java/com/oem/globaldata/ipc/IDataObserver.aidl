package com.oem.globaldata.ipc;
import android.os.Bundle;
/**
 * 数据变化的 oneway Binder 回调。通知只提示变化，客户端重新读取当前值，
 * 避免通知在排队期间过时，也避免把业务 payload 重复通过 Binder 发送。
 * oneway 使系统服务不必同步等待客户端处理回调。
 */
oneway interface IDataObserver {
    /** 成功提交写入或删除后，event 携带地址、操作码、revision 和服务 epoch。 */
    void onDataChanged(in Bundle event);
    /** 通知队列溢出时提示重新同步；serviceEpoch 标识发出提示的服务实例。 */
    void onResyncRequired(String serviceEpoch);
}
