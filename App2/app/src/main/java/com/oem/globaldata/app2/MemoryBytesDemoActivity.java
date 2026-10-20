package com.oem.globaldata.app2;

import android.os.Bundle;
import com.oem.globaldata.app2.databinding.ActivityMemoryBytesDemoBinding;
import com.oem.globaldata.client.DataKey;
import com.oem.globaldata.client.DataListener;
import com.oem.globaldata.client.DataSnapshot;
import com.oem.globaldata.contracts.DemoValueText;
import com.oem.globaldata.contracts.DeviceDataKeys;

/** App2 从内存暂存读取并监听原始字节；与 App1 使用同一个类型化地址。 */
public final class MemoryBytesDemoActivity extends DemoActivityBase {
    private ActivityMemoryBytesDemoBinding binding;
    private final DataKey<byte[]> key = DeviceDataKeys.memoryBytesDiagnosticBytes(BuildConfig.SYSTEM_SCOPE);

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        binding = ActivityMemoryBytesDemoBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());
        bindStatus(binding.status);
        binding.readButton.setOnClickListener(v -> readOnce());
        binding.listenButton.setOnClickListener(v -> listen());
        listen();
    }

    private void readOnce() {
        manager.read(key).whenComplete((snapshot, error) -> {
            if (error != null) error("读取字节", error);
            else showBytes("读取", snapshot);
        });
    }

    private void listen() {
        // 通知只是变化提示；SDK 重读后才把权威快照交给本回调。
        keep(0, manager.observeAndRead(key, getMainExecutor(), new DataListener<byte[]>() {
            @Override public void onChanged(DataSnapshot<byte[]> snapshot) { showBytes("监听", snapshot); }
            @Override public void onResyncRequired() { message("服务重启或通知溢出，正在重读"); }
            @Override public void onError(Throwable failure) { error("监听字节", failure); }
        }));
    }

    private void showBytes(String action, DataSnapshot<byte[]> snapshot) {
        message(action + "：present=" + snapshot.present + "，HEX="
                + (snapshot.present ? DemoValueText.display(snapshot.value) : "无")
                + "，revision=" + snapshot.revision + "，epoch=" + snapshot.serviceEpoch);
    }
}
