package com.oem.globaldata.client;

import android.os.Bundle;
import com.oem.globaldata.ipc.Wire;

/** 无载荷事件的地址；同一 scope、owner、topic 才属于同一事件流。 */
public final class EventTopic {
    /** 事件的设备级或用户级地址范围，授权也按此范围区分。 */
    public final int scope;
    /** owner 管理发布/订阅授权；topic 是需要精确匹配的完整事件名。 */
    public final String ownerPackage, topic;
    /** 事件地址只从工厂创建，保持字段组合固定。 */
    private EventTopic(int scope, String ownerPackage, String topic) {
        this.scope = scope; this.ownerPackage = ownerPackage; this.topic = topic;
    }
    /**
     * 定义事件 topic；事件本身没有 Store，也不携带业务对象。
     * @param scope 事件的设备/用户范围
     * @param ownerPackage 管理该 topic 授权的包名
     * @param topic 完整主题名，普通应用使用 events.&lt;owner&gt;/ 前缀
     * @return 不可变的事件地址
     */
    public static EventTopic of(int scope, String ownerPackage, String topic) {
        return new EventTopic(scope, ownerPackage, topic);
    }
    /** @return 只含 scope、owner 和 topic 的传输 Bundle，不包含 payload */
    public Bundle wire() {
        Bundle b = new Bundle(); b.putInt(Wire.SCOPE, scope); b.putString(Wire.OWNER, ownerPackage);
        b.putString(Wire.TOPIC, topic); return b;
    }
}
