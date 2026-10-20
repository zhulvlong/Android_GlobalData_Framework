package com.oem.globaldata.app1;

import android.os.Bundle;
import com.oem.globaldata.app1.databinding.ActivityFileIntDemoBinding;
import com.oem.globaldata.client.DataKey;
import com.oem.globaldata.contracts.DeviceDataKeys;
import com.oem.globaldata.ipc.Wire;

/** App1 写入 文件持久化 · int 操作计数；授权属于 App1 的共享 namespace。 */
public final class FileIntDemoActivity extends DemoActivityBase {
    private ActivityFileIntDemoBinding binding;
    /** App1/App2 从同一 Contracts AAR 取得完全相同的数据地址。 */
    private final DataKey<Integer> key = DeviceDataKeys.fileIntOperationCount(BuildConfig.SYSTEM_SCOPE);

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        // XML 中声明所有控件；生成的 binding 字段替代手工查找 ID。
        binding = ActivityFileIntDemoBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());
        bindStatus(binding.status);
        binding.initialButton.setOnClickListener(v -> write(9999));
        binding.updateButton.setOnClickListener(v -> writeInput());
    }

    private void writeInput() {
        try { write(Integer.parseInt(binding.valueInput.getText().toString().trim())); }
        catch (NumberFormatException error) { invalid("请输入有效的 32 位整数"); }
    }

    private void write(int value) {
        // READ 允许 App2 读取；OBSERVE 允许注册监听，两者缺一不可。
        manager.grantData(key, DeviceDataKeys.APP2_PACKAGE, Wire.READ | Wire.OBSERVE)
                .thenCompose(ignored -> manager.put(key, value))
                .whenComplete((written, error) ->
                        report("写入操作计数", written == null ? null : "revision=" + written.revision, error));
    }
}
