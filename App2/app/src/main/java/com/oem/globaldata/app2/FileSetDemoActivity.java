package com.oem.globaldata.app2;

import android.os.Bundle;
import android.view.View;
import android.widget.AdapterView;
import com.oem.globaldata.app2.databinding.ActivityFileSetDemoBinding;
import com.oem.globaldata.client.DataKey;
import com.oem.globaldata.client.DataListener;
import com.oem.globaldata.client.DataSnapshot;
import com.oem.globaldata.contracts.DeviceDataKeys;

/** App2 的文件持久化 Set 页面：六种类型分别读取和监听各自的 Key。 */
public final class FileSetDemoActivity extends DemoActivityBase {
    private ActivityFileSetDemoBinding binding;
    private int selectionGeneration;

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        binding = ActivityFileSetDemoBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());
        bindStatus(binding.status);
        binding.typeSelector.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                // 旧回调带着上一代编号，即使迟到也不能覆盖新类型的状态。
                ++selectionGeneration;
                message("已切换到 " + parent.getItemAtPosition(position) + " Set，正在订阅");
                listen();
            }
            @Override public void onNothingSelected(AdapterView<?> parent) {}
        });
        binding.readButton.setOnClickListener(v -> readOnce());
        binding.listenButton.setOnClickListener(v -> listen());
        // Spinner 通常自动通知默认项；某些 UI 时序下补一次初始订阅。
        binding.typeSelector.post(() -> {
            if (selectionGeneration == 0 && !isDestroyed()) {
                ++selectionGeneration;
                listen();
            }
        });
    }

    private void readOnce() {
        int token = selectionGeneration;
        switch (binding.typeSelector.getSelectedItemPosition()) {
            case 0: readKey(DeviceDataKeys.fileSetStringEnabledFeatureFlags(BuildConfig.SYSTEM_SCOPE), token); return;
            case 1: readKey(DeviceDataKeys.fileSetIntSupportedChannelIds(BuildConfig.SYSTEM_SCOPE), token); return;
            case 2: readKey(DeviceDataKeys.fileSetLongActiveSessionIds(BuildConfig.SYSTEM_SCOPE), token); return;
            case 3: readKey(DeviceDataKeys.fileSetFloatThresholdRatios(BuildConfig.SYSTEM_SCOPE), token); return;
            case 4: readKey(DeviceDataKeys.fileSetDoubleTemperatureAlarmThresholdsCelsius(BuildConfig.SYSTEM_SCOPE), token); return;
            case 5: readKey(DeviceDataKeys.fileSetBooleanObservedBinaryStates(BuildConfig.SYSTEM_SCOPE), token); return;
            default: error("Set读取", new IllegalArgumentException("未知集合类型"));
        }
    }

    private void listen() {
        int token = selectionGeneration;
        switch (binding.typeSelector.getSelectedItemPosition()) {
            case 0: listenKey(DeviceDataKeys.fileSetStringEnabledFeatureFlags(BuildConfig.SYSTEM_SCOPE), token); return;
            case 1: listenKey(DeviceDataKeys.fileSetIntSupportedChannelIds(BuildConfig.SYSTEM_SCOPE), token); return;
            case 2: listenKey(DeviceDataKeys.fileSetLongActiveSessionIds(BuildConfig.SYSTEM_SCOPE), token); return;
            case 3: listenKey(DeviceDataKeys.fileSetFloatThresholdRatios(BuildConfig.SYSTEM_SCOPE), token); return;
            case 4: listenKey(DeviceDataKeys.fileSetDoubleTemperatureAlarmThresholdsCelsius(BuildConfig.SYSTEM_SCOPE), token); return;
            case 5: listenKey(DeviceDataKeys.fileSetBooleanObservedBinaryStates(BuildConfig.SYSTEM_SCOPE), token); return;
            default: error("Set监听", new IllegalArgumentException("未知集合类型"));
        }
    }

    private <T> void readKey(DataKey<T> key, int token) {
        manager.read(key).whenComplete((snapshot, failure) -> runOnUiThread(() -> {
            // Future 可在后台完成；先回主线程再读 Spinner 并核对当前类型。
            if (token != selectionGeneration || isDestroyed()) return;
            if (failure != null) error("Set读取", failure);
            else display("读取", snapshot);
        }));
    }

    private <T> void listenKey(DataKey<T> key, int token) {
        // keep 关闭旧 Subscription；回调再检查类型代数，避免旧 Key 的迟到结果串页。
        keep(0, manager.observeAndRead(key, getMainExecutor(), new DataListener<T>() {
            @Override public void onChanged(DataSnapshot<T> snapshot) {
                if (token == selectionGeneration && !isDestroyed()) display("监听", snapshot);
            }
            @Override public void onResyncRequired() {
                if (token == selectionGeneration && !isDestroyed()) message("服务重启或通知溢出，正在重读");
            }
            @Override public void onError(Throwable failure) {
                if (token == selectionGeneration && !isDestroyed()) error("Set监听", failure);
            }
        }));
    }

    private void display(String action, DataSnapshot<?> snapshot) {
        message("Set " + binding.typeSelector.getSelectedItem() + " " + action
                + "：present=" + snapshot.present + "，值="
                + (snapshot.present ? snapshot.value : "尚未写入")
                + "，revision=" + snapshot.revision + "，epoch=" + snapshot.serviceEpoch);
    }
}
