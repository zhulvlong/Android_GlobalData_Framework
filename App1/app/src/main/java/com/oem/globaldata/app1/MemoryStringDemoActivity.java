package com.oem.globaldata.app1;

import android.os.Bundle;
import com.oem.globaldata.app1.databinding.ActivityMemoryStringDemoBinding;
import com.oem.globaldata.client.DataKey;
import com.oem.globaldata.contracts.DeviceDataKeys;
import com.oem.globaldata.ipc.Wire;

/** App1 写入 内存暂存 · String 欢迎消息；授权属于 App1 的共享 namespace。 */
public final class MemoryStringDemoActivity extends DemoActivityBase {
    private ActivityMemoryStringDemoBinding binding;
    /** App1/App2 从同一 Contracts AAR 取得完全相同的数据地址。 */
    private final DataKey<String> key = DeviceDataKeys.memoryStringWelcomeMessage(BuildConfig.SYSTEM_SCOPE);

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        // XML 中声明所有控件；生成的 binding 字段替代手工查找 ID。
        binding = ActivityMemoryStringDemoBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());
        bindStatus(binding.status);
        binding.initialButton.setOnClickListener(v -> write("欢迎使用 App1"));
        binding.updateButton.setOnClickListener(v -> write(binding.messageInput.getText().toString()));
    }

    private void write(String message) {
        // 先授权后写入，App2 打开对应页面即可读取并持续监听。
        manager.grantData(key, DeviceDataKeys.APP2_PACKAGE, Wire.READ | Wire.OBSERVE)
                .thenCompose(ignored -> manager.put(key, message))
                .whenComplete((written, error) ->
                        report("写入欢迎消息", written == null ? null : "revision=" + written.revision, error));
    }
}
