package com.oem.globaldata.app1;

import android.os.Bundle;
import com.oem.globaldata.app1.databinding.ActivityDatabaseBytesDemoBinding;
import com.oem.globaldata.client.DataKey;
import com.oem.globaldata.contracts.DemoValueText;
import com.oem.globaldata.contracts.DeviceDataKeys;
import com.oem.globaldata.ipc.Wire;

/** App1 在SQLite 持久化托管原始诊断字节；系统服务不解释 byte[] 内容。 */
public final class DatabaseBytesDemoActivity extends DemoActivityBase {
    private ActivityDatabaseBytesDemoBinding binding;
    private final DataKey<byte[]> key = DeviceDataKeys.databaseBytesDiagnosticBytes(BuildConfig.SYSTEM_SCOPE);

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        binding = ActivityDatabaseBytesDemoBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());
        bindStatus(binding.status);
        binding.initialButton.setOnClickListener(v -> write(new byte[] {0x0A, (byte) 0xFF, 0x01}));
        binding.updateButton.setOnClickListener(v -> {
            try { write(DemoValueText.parseHex(binding.bytesInput.getText().toString())); }
            catch (IllegalArgumentException error) { invalid(error.getMessage()); }
        });
    }

    private void write(byte[] value) {
        // App2 的 READ 与 OBSERVE 作用于共享 namespace，而非只作用于本 Key。
        manager.grantData(key, DeviceDataKeys.APP2_PACKAGE, Wire.READ | Wire.OBSERVE)
                .thenCompose(ignored -> manager.put(key, value))
                .whenComplete((written, error) -> report("写入原始字节",
                        written == null ? null : "revision=" + written.revision, error));
    }
}
