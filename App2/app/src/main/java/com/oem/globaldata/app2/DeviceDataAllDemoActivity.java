package com.oem.globaldata.app2;

import android.os.Bundle;
import com.oem.globaldata.app2.databinding.ActivityDataAudienceDemoBinding;
import com.oem.globaldata.client.DataKey;
import com.oem.globaldata.client.DataListener;
import com.oem.globaldata.client.DataSnapshot;
import com.oem.globaldata.contracts.DeviceDataKeys;

/** App2 直接使用同一 Key 读取和监听，授权判断由系统服务完成。 */
public final class DeviceDataAllDemoActivity extends DemoActivityBase {
    private int listenAttempt;

    private final DataKey<String> key = DeviceDataKeys.fileStringDeviceAudienceDemo();

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        ActivityDataAudienceDemoBinding binding =
                ActivityDataAudienceDemoBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());
        bindStatus(binding.status);
        binding.title.setText(R.string.audience_device_all_title);
        binding.address.setText(getString(R.string.audience_device_all_address, key.ownerPackage, key.key));
        binding.requirements.setText(R.string.audience_device_all_requirements);
        binding.status.setText("可先读取验证未授权。App1 授权后，再读取或开始监听。");

        binding.readButton.setOnClickListener(v -> manager.read(key)
                .whenComplete((snapshot, failure) -> {
                    if (failure != null) error("读取", failure);
                    else show("读取", snapshot);
                }));
        binding.listenButton.setOnClickListener(v -> listen());
    }

    private void listen() {
        // 重试时旧回调可能已排队，避免它覆盖新一轮订阅的状态。
        int attempt = ++listenAttempt;
        keep(0, manager.observeAndRead(key, getMainExecutor(), new DataListener<String>() {
            @Override public void onChanged(DataSnapshot<String> snapshot) {
                if (attempt == listenAttempt) show("监听", snapshot);
            }

            @Override public void onResyncRequired() {
                if (attempt == listenAttempt) message("服务重启或通知溢出，正在重读");
            }

            @Override public void onError(Throwable failure) {
                if (attempt == listenAttempt) error("监听", failure);
            }
        }));
    }
}
