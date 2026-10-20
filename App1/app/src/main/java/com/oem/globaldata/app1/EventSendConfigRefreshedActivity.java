package com.oem.globaldata.app1;

import android.os.Bundle;
import com.oem.globaldata.app1.databinding.ActivityEventSendConfigRefreshedBinding;
import com.oem.globaldata.client.EventTopic;
import com.oem.globaldata.contracts.DeviceDataKeys;
import com.oem.globaldata.contracts.DeviceTopics;
import com.oem.globaldata.ipc.Wire;

/** App1 发布“配置已刷新”；本事件只携带 topic，不传递配置对象。 */
public final class EventSendConfigRefreshedActivity extends DemoActivityBase {
    private ActivityEventSendConfigRefreshedBinding binding;
    private final EventTopic topic = DeviceTopics.app1ToApp2ConfigRefreshedEvent(BuildConfig.SYSTEM_SCOPE);

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        // XML 布局声明状态文字和按钮；由 Data Binding 直接取得控件。
        binding = ActivityEventSendConfigRefreshedBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());
        bindStatus(binding.status);
        binding.grantButton.setOnClickListener(v ->
                manager.grantEvent(topic, DeviceDataKeys.APP2_PACKAGE, Wire.SUBSCRIBE)
                        .whenComplete((ignored, failure) -> report("授权订阅", "已授权", failure)));
        binding.sendButton.setOnClickListener(v -> {
            // post 的成功只表示服务端接受事件，不保证每个订阅者已经收到。
            manager.events().post(topic)
                    .whenComplete((ignored, failure) -> report("发送无载荷事件", "已提交", failure));
        });
    }
}
