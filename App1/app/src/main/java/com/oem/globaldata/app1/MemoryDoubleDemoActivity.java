package com.oem.globaldata.app1;

import android.os.Bundle;
import com.oem.globaldata.app1.databinding.ActivityMemoryDoubleDemoBinding;
import com.oem.globaldata.client.DataKey;
import com.oem.globaldata.contracts.DeviceDataKeys;
import com.oem.globaldata.ipc.Wire;

/** App1 演示内存暂存中的 double 传感器温度（°C）：授权 App2 后写入和修改。 */
public final class MemoryDoubleDemoActivity extends DemoActivityBase {
    private ActivityMemoryDoubleDemoBinding binding;
    /** 与 App2 共用同一业务地址，Codec 在 Contracts 中固定。 */
    private final DataKey<Double> key =
            DeviceDataKeys.memoryDoubleSensorTemperatureCelsius(BuildConfig.SYSTEM_SCOPE);

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        // 控件全部定义在 XML，通过 Data Binding 取得强类型字段。
        binding = ActivityMemoryDoubleDemoBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());
        bindStatus(binding.status);
        binding.initialButton.setOnClickListener(v -> write(23.625d));
        binding.updateButton.setOnClickListener(v -> writeInput());
    }

    private void writeInput() {
        try {
            // parseFloat/parseDouble 接受普通小数及 NaN、Infinity；输入按 IEEE 754 编码。
            write(Double.parseDouble(binding.valueInput.getText().toString().trim()));
        } catch (NumberFormatException error) {
            invalid("请输入有效的 double 数值");
        }
    }

    private void write(double value) {
        // 同一 namespace 授权覆盖三种 Store；READ 和 OBSERVE 都是 App2 监听所需。
        manager.grantData(key, DeviceDataKeys.APP2_PACKAGE, Wire.READ | Wire.OBSERVE)
                .thenCompose(ignored -> manager.put(key, value))
                .whenComplete((written, error) -> report("写入传感器温度（°C）",
                        written == null ? null : "值=" + value + "，revision=" + written.revision,
                        error));
    }
}
