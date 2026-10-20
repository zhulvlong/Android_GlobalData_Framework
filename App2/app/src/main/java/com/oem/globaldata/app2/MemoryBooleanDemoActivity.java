package com.oem.globaldata.app2;

import android.os.Bundle;
import com.oem.globaldata.app2.databinding.ActivityMemoryBooleanDemoBinding;
import com.oem.globaldata.client.DataKey;
import com.oem.globaldata.client.DataListener;
import com.oem.globaldata.client.DataSnapshot;
import com.oem.globaldata.contracts.DeviceDataKeys;

/** App2 读取与监听 内存暂存 · boolean 通知开关；App1 须先授予 READ 和 OBSERVE。 */
public final class MemoryBooleanDemoActivity extends DemoActivityBase {
    private ActivityMemoryBooleanDemoBinding binding;
    /** 与 App1 写入页完全相同的地址，owner 字段不代表 App2 自己是 owner。 */
    private final DataKey<Boolean> key = DeviceDataKeys.memoryBooleanNotificationsEnabled(BuildConfig.SYSTEM_SCOPE);

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        // 读取页布局独立维护；按钮、状态和集合视图都来自 Data Binding。
        binding = ActivityMemoryBooleanDemoBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());
        bindStatus(binding.status);
        binding.readButton.setOnClickListener(v -> readOnce());
        binding.listenButton.setOnClickListener(v -> listen());
        listen();
    }

    private void readOnce() {
        // read 根据 Key 中的 Codec 解码；不存在时得到 present=false，而不是异常。
        manager.read(key).whenComplete((snapshot, failure) -> {
            if (failure != null) error("读取", failure);
            else show("读取", snapshot);
        });
    }

    private void listen() {
        // observeAndRead 先注册再读取，后续通知会触发 SDK 重读最新值。
        keep(0, manager.observeAndRead(key, getMainExecutor(), new DataListener<Boolean>() {
            @Override public void onChanged(DataSnapshot<Boolean> snapshot) {
                show("监听", snapshot);
            }
            @Override public void onResyncRequired() { message("服务重启或通知溢出，正在重读"); }
            @Override public void onError(Throwable failure) { error("监听", failure); }
        }));
    }

}
