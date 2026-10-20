package com.oem.globaldata.app1;

import android.os.Bundle;
import com.oem.globaldata.app1.databinding.ActivityDatabaseLongDemoBinding;
import com.oem.globaldata.client.DataKey;
import com.oem.globaldata.contracts.DeviceDataKeys;
import com.oem.globaldata.ipc.Wire;

/** App1 演示数据库持久化中的 long 累计传输字节数：授权 App2 后写入和修改。 */
public final class DatabaseLongDemoActivity extends DemoActivityBase {
    private ActivityDatabaseLongDemoBinding binding;
    /** 与 App2 共用同一业务地址，Codec 在 Contracts 中固定。 */
    private final DataKey<Long> key =
            DeviceDataKeys.databaseLongTotalTransferBytes(BuildConfig.SYSTEM_SCOPE);

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        // 控件全部定义在 XML，通过 Data Binding 取得强类型字段。
        binding = ActivityDatabaseLongDemoBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());
        bindStatus(binding.status);
        binding.initialButton.setOnClickListener(v -> write(4_294_967_296L));
        binding.updateButton.setOnClickListener(v -> writeInput());
    }

    private void writeInput() {
        try {
            // long 的 64 位范围由 parseLong 检查，避免把超范围输入截断。
            write(Long.parseLong(binding.valueInput.getText().toString().trim()));
        } catch (NumberFormatException error) {
            invalid("请输入有效的 64 位整数");
        }
    }

    private void write(long value) {
        // 同一 namespace 授权覆盖三种 Store；READ 和 OBSERVE 都是 App2 监听所需。
        manager.grantData(key, DeviceDataKeys.APP2_PACKAGE, Wire.READ | Wire.OBSERVE)
                .thenCompose(ignored -> manager.put(key, value))
                .whenComplete((written, error) -> report("写入累计传输字节数",
                        written == null ? null : "值=" + value + "，revision=" + written.revision,
                        error));
    }
}
