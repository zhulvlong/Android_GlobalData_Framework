package com.oem.globaldata.ipc;
import android.os.Bundle;
/** 在线事件的异步 Binder 回调；事件不保存，也没有业务载荷。 */
oneway interface IEventListener {
    /** event 只包含 scope、owner、topic 和服务 epoch 等元信息。 */
    void onEvent(in Bundle event);
}
