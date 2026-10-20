package com.oem.globaldata.app2;

import android.os.Bundle;
import com.oem.globaldata.app2.databinding.ActivityEventSendRefreshRequestBinding;
import com.oem.globaldata.client.EventTopic;
import com.oem.globaldata.contracts.DeviceTopics;

/** App2 向 App1 发送无载荷刷新请求。 */
public final class EventSendRefreshRequestActivity extends DemoActivityBase {
    private ActivityEventSendRefreshRequestBinding binding;
    private final EventTopic topic = DeviceTopics.app2ToApp1ConfigRefreshRequestEvent(BuildConfig.SYSTEM_SCOPE);

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        // XML 布局声明状态文字和按钮；由 Data Binding 直接取得控件。
        binding = ActivityEventSendRefreshRequestBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());
        bindStatus(binding.status);
        binding.sendButton.setOnClickListener(v -> {
            // 真实 Binder UID 必须具有此完整 topic 的 PUBLISH 权限。
            manager.events().post(topic)
                    .whenComplete((ignored, failure) ->
                            report("发送刷新请求", failure == null ? "已提交" : null, failure));
        });
    }
}
