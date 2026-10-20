package com.oem.globaldata.app1;

import android.os.Bundle;
import android.view.View;
import android.widget.AdapterView;
import com.oem.globaldata.app1.databinding.ActivityMemorySetDemoBinding;
import com.oem.globaldata.client.DataKey;
import com.oem.globaldata.client.WriteResult;
import com.oem.globaldata.contracts.DeviceDataKeys;
import com.oem.globaldata.ipc.Wire;

/** App1 的内存暂存 Set 页面：每种类型对应明确的业务 Key 与独立 Codec。 */
public final class MemorySetDemoActivity extends DemoActivityBase {
    private ActivityMemorySetDemoBinding binding;

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        binding = ActivityMemorySetDemoBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());
        bindStatus(binding.status);
        binding.typeSelector.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                // 切换类型时换成该类型的可编辑样例，防止将上一类型文本误写到新 Key。
                binding.flagsInput.setText(CollectionValueInputs.sample("Set", position));
                invalid("已选择 " + CollectionValueInputs.typeName(position) + " Set");
            }
            @Override public void onNothingSelected(AdapterView<?> parent) {}
        });
        binding.initialButton.setOnClickListener(v -> write(true));
        binding.updateButton.setOnClickListener(v -> write(false));
    }

    private void write(boolean initial) {
        int type = binding.typeSelector.getSelectedItemPosition();
        String input = initial ? CollectionValueInputs.sample("Set", type)
                : binding.flagsInput.getText().toString();
        try {
            // 每个分支直接使用相应的 DataKey<T>，编译器检查值与 Codec 的泛型一致。
            switch (type) {
                case 0:
                    submit(DeviceDataKeys.memorySetStringEnabledFeatureFlags(BuildConfig.SYSTEM_SCOPE),
                            CollectionValueInputs.set(input, (String text) -> text), "String");
                    return;
                case 1:
                    submit(DeviceDataKeys.memorySetIntSupportedChannelIds(BuildConfig.SYSTEM_SCOPE),
                            CollectionValueInputs.set(input, Integer::valueOf), "Integer");
                    return;
                case 2:
                    submit(DeviceDataKeys.memorySetLongActiveSessionIds(BuildConfig.SYSTEM_SCOPE),
                            CollectionValueInputs.set(input, Long::valueOf), "Long");
                    return;
                case 3:
                    submit(DeviceDataKeys.memorySetFloatThresholdRatios(BuildConfig.SYSTEM_SCOPE),
                            CollectionValueInputs.set(input, Float::valueOf), "Float");
                    return;
                case 4:
                    submit(DeviceDataKeys.memorySetDoubleTemperatureAlarmThresholdsCelsius(BuildConfig.SYSTEM_SCOPE),
                            CollectionValueInputs.set(input, Double::valueOf), "Double");
                    return;
                case 5:
                    submit(DeviceDataKeys.memorySetBooleanObservedBinaryStates(BuildConfig.SYSTEM_SCOPE),
                            CollectionValueInputs.set(input, CollectionValueInputs::booleanValue), "Boolean");
                    return;
                default: throw new IllegalArgumentException("未知集合类型");
            }
        } catch (RuntimeException failure) {
            invalid("输入无效：" + failure.getMessage());
        }
    }

    private <T> void submit(DataKey<T> key, T value, String type) {
        // owner App1 先授予整个 namespace 的 READ/OBSERVE，再写入当前类型的 Key。
        manager.grantData(key, DeviceDataKeys.APP2_PACKAGE, Wire.READ | Wire.OBSERVE)
                .thenCompose(ignored -> manager.put(key, value))
                .whenComplete((written, error) -> report("Set " + type,
                        written == null ? null : "present=true，revision=" + written.revision
                                + "，epoch=" + written.serviceEpoch, error));
    }
}
