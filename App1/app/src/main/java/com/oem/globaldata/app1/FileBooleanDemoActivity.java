package com.oem.globaldata.app1;

import android.os.Bundle;
import com.oem.globaldata.app1.databinding.ActivityFileBooleanDemoBinding;
import com.oem.globaldata.client.DataKey;
import com.oem.globaldata.contracts.DeviceDataKeys;
import com.oem.globaldata.ipc.Wire;

/** App1 写入 文件持久化 · boolean 通知开关；授权属于 App1 的共享 namespace。 */
public final class FileBooleanDemoActivity extends DemoActivityBase {
    private ActivityFileBooleanDemoBinding binding;
    /** App1/App2 从同一 Contracts AAR 取得完全相同的数据地址。 */
    private final DataKey<Boolean> key = DeviceDataKeys.fileBooleanNotificationsEnabled(BuildConfig.SYSTEM_SCOPE);

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        // XML 中声明所有控件；生成的 binding 字段替代手工查找 ID。
        binding = ActivityFileBooleanDemoBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());
        bindStatus(binding.status);
        binding.initialButton.setOnClickListener(v -> write(true));
        binding.updateButton.setOnClickListener(v -> write(binding.enabledInput.isChecked()));
    }

    private void write(boolean enabled) {
        // App2 需要 READ 读取值，还需要 OBSERVE 接收变化提示。
        manager.grantData(key, DeviceDataKeys.APP2_PACKAGE, Wire.READ | Wire.OBSERVE)
                .thenCompose(ignored -> manager.put(key, enabled))
                .whenComplete((written, error) ->
                        report("写入通知开关", written == null ? null : "revision=" + written.revision, error));
    }
}
