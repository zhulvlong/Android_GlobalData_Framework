package com.oem.globaldata.app2;

import android.os.Bundle;
import com.oem.globaldata.app2.databinding.ActivityEventReceiveConfigRefreshedBinding;
import com.oem.globaldata.client.EventTopic;
import com.oem.globaldata.client.Subscription;
import com.oem.globaldata.contracts.DeviceTopics;
import java.util.concurrent.CompletableFuture;

/** App2 在线接收 App1 的无载荷“配置已刷新”事件。 */
public final class EventReceiveConfigRefreshedActivity extends DemoActivityBase {
    private ActivityEventReceiveConfigRefreshedBinding binding;
    private final EventTopic topic = DeviceTopics.app1ToApp2ConfigRefreshedEvent(BuildConfig.SYSTEM_SCOPE);
    private int received;
    private int request;

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        // XML 布局声明状态文字和按钮；由 Data Binding 直接取得控件。
        binding = ActivityEventReceiveConfigRefreshedBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());
        bindStatus(binding.status);
        binding.listenButton.setOnClickListener(v -> listen());
        listen();
    }

    private void listen() {
        int current = ++request;
        // keep 关闭旧订阅，并在 Activity 销毁时释放新的订阅。
        CompletableFuture<Subscription> pending = manager.events().subscribe(topic, getMainExecutor(),
                event -> message("收到 CONFIG_REFRESHED，次数=" + (++received)
                        + "；需要配置内容时另行读取 Key"));
        keep(0, pending);
        pending.thenAccept(ignored -> {
            if (current == request && !isDestroyed()) message("订阅成功，正在等待 App1 发布事件");
        });
    }
}
