package com.oem.globaldata.app1;

import android.os.Bundle;
import com.oem.globaldata.app1.databinding.ActivityMemoryFloatDemoBinding;
import com.oem.globaldata.client.DataKey;
import com.oem.globaldata.contracts.DeviceDataKeys;
import com.oem.globaldata.ipc.Wire;

/** App1 演示内存暂存中的 float 下载进度比例：授权 App2 后写入和修改。 */
public final class MemoryFloatDemoActivity extends DemoActivityBase {
    private ActivityMemoryFloatDemoBinding binding;
    /** 与 App2 共用同一业务地址，Codec 在 Contracts 中固定。 */
    private final DataKey<Float> key =
            DeviceDataKeys.memoryFloatDownloadProgressRatio(BuildConfig.SYSTEM_SCOPE);

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        // 控件全部定义在 XML，通过 Data Binding 取得强类型字段。
        binding = ActivityMemoryFloatDemoBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());
        bindStatus(binding.status);
        binding.initialButton.setOnClickListener(v -> write(0.75f));
        binding.updateButton.setOnClickListener(v -> writeInput());
    }

    private void writeInput() {
        try {
            // parseFloat/parseDouble 接受普通小数及 NaN、Infinity；输入按 IEEE 754 编码。
            write(Float.parseFloat(binding.valueInput.getText().toString().trim()));
        } catch (NumberFormatException error) {
            invalid("请输入有效的 float 数值");
        }
    }

    private void write(float value) {
        // 为 App2 授予当前 Key 的 READ 与 OBSERVE，以便读取并监听该值。
        manager.grantData(key, DeviceDataKeys.APP2_PACKAGE, Wire.READ | Wire.OBSERVE)
                .thenCompose(ignored -> manager.put(key, value))
                .whenComplete((written, error) -> report("写入下载进度比例",
                        written == null ? null : "值=" + value + "，revision=" + written.revision,
                        error));
    }
}
