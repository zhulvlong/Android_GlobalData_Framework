package com.oem.globaldata.app1;

import android.os.Bundle;

import com.oem.globaldata.app1.databinding.ActivityEventReceiveRefreshRequestBinding;
import com.oem.globaldata.client.EventTopic;
import com.oem.globaldata.client.Subscription;
import com.oem.globaldata.contracts.DeviceDataKeys;
import com.oem.globaldata.contracts.DeviceTopics;
import com.oem.globaldata.ipc.Wire;

/**
 * App1 在线接收 App2 的刷新请求；消息里没有业务对象。
 */
public final class EventReceiveRefreshRequestActivity extends DemoActivityBase {
    private ActivityEventReceiveRefreshRequestBinding binding;
    private final EventTopic topic = DeviceTopics.app2ToApp1ConfigRefreshRequestEvent(BuildConfig.SYSTEM_SCOPE);
    private Subscription subscription;
    private int request, received;

    @Override
    public void onCreate(Bundle state) {
        super.onCreate(state);
        // XML 布局声明状态文字和按钮；由 Data Binding 直接取得控件。
        binding = ActivityEventReceiveRefreshRequestBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());
        bindStatus(binding.status);
        binding.grantButton.setOnClickListener(v ->
                manager.grantEvent(topic, DeviceDataKeys.APP2_PACKAGE, Wire.PUBLISH)
                        .whenComplete((ignored, failure) -> report("授权发布", "已授权", failure)));
        binding.listenButton.setOnClickListener(v -> listen());
        listen();
    }

    private void listen() {
        int current = ++request;
        if (subscription != null) subscription.close();
        subscription = null;
        // 回调只返回本地约定的 EventTopic；接收次数只统计本页在线期间的事件。
        manager.events().subscribe(topic, getMainExecutor(), event -> {
            if (current == request && !isDestroyed())
                report("收到刷新请求", "次数=" + (++received), null);
        }).whenComplete((created, failure) -> runOnUiThread(() -> {
            if (isDestroyed() || current != request) {
                if (created != null) {
                    created.close();
                }
                return;
            }
            if (failure != null) {
                report("订阅刷新请求", null, failure);
            } else {
                subscription = created;
                report("订阅刷新请求", "正在接收", null);
            }
        }));
    }

    @Override
    public void onDestroy() {
        ++request;
        if (subscription != null) subscription.close();
        super.onDestroy();
    }
}
