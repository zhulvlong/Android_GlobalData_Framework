package com.oem.globaldata.app2;

import android.os.Bundle;
import com.oem.globaldata.app2.databinding.ActivityDatabaseFloatDemoBinding;
import com.oem.globaldata.client.DataKey;
import com.oem.globaldata.client.DataListener;
import com.oem.globaldata.client.DataSnapshot;
import com.oem.globaldata.contracts.DeviceDataKeys;

/** App2 演示读取和监听数据库持久化中的 float 下载进度比例。 */
public final class DatabaseFloatDemoActivity extends DemoActivityBase {
    private ActivityDatabaseFloatDemoBinding binding;
    /** 地址与 App1 完全一致；owner 仍为 App1，App2 依靠授权访问。 */
    private final DataKey<Float> key =
            DeviceDataKeys.databaseFloatDownloadProgressRatio(BuildConfig.SYSTEM_SCOPE);

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        // 页面视图均来自独立 XML 布局，没有动态创建或按 ID 查找控件。
        binding = ActivityDatabaseFloatDemoBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());
        bindStatus(binding.status);
        binding.readButton.setOnClickListener(v -> readOnce());
        binding.listenButton.setOnClickListener(v -> listen());
        listen();
    }

    private void readOnce() {
        // Key 自带 float Codec；不存在时 SDK 返回 present=false。
        manager.read(key).whenComplete((snapshot, failure) -> {
            if (failure != null) error("读取", failure);
            else show("读取", snapshot);
        });
    }

    private void listen() {
        // 先订阅再读取；重新点击按钮会由基类关闭旧订阅。
        keep(0, manager.observeAndRead(key, getMainExecutor(), new DataListener<Float>() {
            @Override public void onChanged(DataSnapshot<Float> snapshot) {
                show("监听", snapshot);
            }
            @Override public void onResyncRequired() { message("服务重启或通知溢出，正在重读"); }
            @Override public void onError(Throwable failure) { error("监听", failure); }
        }));
    }
}
