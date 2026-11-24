package com.oem.globaldata.app1;

import android.os.Bundle;
import com.oem.globaldata.app1.databinding.ActivityDataAudienceDemoBinding;
import com.oem.globaldata.client.DataKey;
import com.oem.globaldata.contracts.DeviceDataKeys;
import com.oem.globaldata.ipc.Wire;
import java.time.Instant;

/** 演示 owner 对一条公开示例数据声明、使用和撤销范围授权。 */
public final class UserDataAllDemoActivity extends DemoActivityBase {
    private static final int READER_RIGHTS = Wire.READ | Wire.OBSERVE;

    private final DataKey<String> key = DeviceDataKeys.memoryStringUserAllAudienceDemo();

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        ActivityDataAudienceDemoBinding binding =
                ActivityDataAudienceDemoBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());
        bindStatus(binding.status);
        binding.title.setText(R.string.audience_user_all_title);
        binding.address.setText(getString(R.string.audience_user_all_address, key.namespace, key.key));
        binding.requirements.setText(R.string.audience_user_all_requirements);
        binding.status.setText("先授权，再写入新示例值。");

        binding.grantButton.setOnClickListener(v -> manager.grantUserDataAll(key, READER_RIGHTS)
                .whenComplete((ignored, failure) ->
                        report("授权", "规则已生效，可随后安装符合条件的 App2", failure)));
        binding.writeButton.setOnClickListener(v -> writeSample());
        binding.revokeButton.setOnClickListener(v -> manager.revokeUserDataAll(key, READER_RIGHTS)
                .whenComplete((ignored, failure) -> report("撤权",
                        "本案例的读取和监听权限已撤销，可继续写入验证 App2", failure)));
    }

    private void writeSample() {
        String value = "范围授权示例 · " + Instant.now();
        // 撤权后 owner 仍可写入。将写入和授权分开，才能验证 App2 停止收到通知。
        manager.put(key, value).whenComplete((written, failure) ->
                report("写入", written == null ? null : value + "，revision=" + written.revision, failure));
    }
}
