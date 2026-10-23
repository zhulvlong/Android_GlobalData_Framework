package com.oem.globaldata.ipc;
import android.os.Bundle;
/**
 * 在线事件的 oneway Binder 回调；只传 topic 到达信号，不保存业务载荷。
 * 这样发送者不依赖接收 App 的处理耗时；需要可靠状态时由数据存储和监听承担。
 */
oneway interface IEventListener {
    /** event 仅含 scope、owner、topic 和服务 epoch 等元信息。 */
    void onEvent(in Bundle event);
}
