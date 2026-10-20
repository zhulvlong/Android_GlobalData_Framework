package com.oem.globaldata.app1;

import android.os.Bundle;
import com.oem.globaldata.app1.databinding.ActivityMemoryObjectDemoBinding;
import com.oem.globaldata.client.DataKey;
import com.oem.globaldata.contracts.DeviceConfig;
import com.oem.globaldata.contracts.DeviceDataKeys;
import com.oem.globaldata.ipc.Wire;

/** App1 写入 内存暂存 · DeviceConfig 对象；授权属于 App1 的共享 namespace。 */
public final class MemoryObjectDemoActivity extends DemoActivityBase {
    private ActivityMemoryObjectDemoBinding binding;
    /** App1/App2 从同一 Contracts AAR 取得完全相同的数据地址。 */
    private final DataKey<DeviceConfig> key = DeviceDataKeys.memoryObjectDeviceConfig(BuildConfig.SYSTEM_SCOPE);

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        // XML 中声明所有控件；生成的 binding 字段替代手工查找 ID。
        binding = ActivityMemoryObjectDemoBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());
        bindStatus(binding.status);
        binding.initialButton.setOnClickListener(v ->
                write(new DeviceConfig(1001, "App1 示例设备", true, "demo")));
        binding.updateButton.setOnClickListener(v -> writeInput());
    }

    private void writeInput() {
        try {
            int id = Integer.parseInt(binding.idInput.getText().toString().trim());
            write(new DeviceConfig(id, binding.nameInput.getText().toString(),
                    binding.enabledInput.isChecked(), binding.groupInput.getText().toString()));
        } catch (NumberFormatException error) { invalid("设备编号必须是有效整数"); }
    }

    private void write(DeviceConfig value) {
        // DeviceConfigCodec 在 SDK 侧处理 JSON；服务端不加载 DeviceConfig 类。
        manager.grantData(key, DeviceDataKeys.APP2_PACKAGE, Wire.READ | Wire.OBSERVE)
                .thenCompose(ignored -> manager.put(key, value))
                .whenComplete((written, error) ->
                        report("写入设备配置", written == null ? null : "revision=" + written.revision, error));
    }
}
