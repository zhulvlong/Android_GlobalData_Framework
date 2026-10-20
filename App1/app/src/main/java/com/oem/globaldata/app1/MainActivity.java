package com.oem.globaldata.app1;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import com.oem.globaldata.app1.databinding.ActivityMainBinding;

/** XML 首页目录：每个按钮进入单独的数据或事件演示 Activity。 */
public final class MainActivity extends Activity {
    private ActivityMainBinding binding;

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        // Data Binding 生成的强类型字段与 XML 的 android:id 一一对应。
        binding = ActivityMainBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());
        binding.mode.setText("当前模式：" + (BuildConfig.SYSTEM_SCOPE
                ? "平台签名 / DEVICE_DE / sys.device"
                : "普通 App / USER_CE / App1 共享 namespace"));
        binding.memoryIntButton.setOnClickListener(v -> open(MemoryIntDemoActivity.class));
        binding.memoryLongButton.setOnClickListener(v -> open(MemoryLongDemoActivity.class));
        binding.memoryFloatButton.setOnClickListener(v -> open(MemoryFloatDemoActivity.class));
        binding.memoryDoubleButton.setOnClickListener(v -> open(MemoryDoubleDemoActivity.class));
        binding.memoryBooleanButton.setOnClickListener(v -> open(MemoryBooleanDemoActivity.class));
        binding.memoryStringButton.setOnClickListener(v -> open(MemoryStringDemoActivity.class));
        binding.memoryObjectButton.setOnClickListener(v -> open(MemoryObjectDemoActivity.class));
        binding.memoryListButton.setOnClickListener(v -> open(MemoryListDemoActivity.class));
        binding.memoryMapButton.setOnClickListener(v -> open(MemoryMapDemoActivity.class));
        binding.memorySetButton.setOnClickListener(v -> open(MemorySetDemoActivity.class));
        binding.memoryBytesButton.setOnClickListener(v -> open(MemoryBytesDemoActivity.class));
        binding.fileIntButton.setOnClickListener(v -> open(FileIntDemoActivity.class));
        binding.fileLongButton.setOnClickListener(v -> open(FileLongDemoActivity.class));
        binding.fileFloatButton.setOnClickListener(v -> open(FileFloatDemoActivity.class));
        binding.fileDoubleButton.setOnClickListener(v -> open(FileDoubleDemoActivity.class));
        binding.fileBooleanButton.setOnClickListener(v -> open(FileBooleanDemoActivity.class));
        binding.fileStringButton.setOnClickListener(v -> open(FileStringDemoActivity.class));
        binding.fileObjectButton.setOnClickListener(v -> open(FileObjectDemoActivity.class));
        binding.fileListButton.setOnClickListener(v -> open(FileListDemoActivity.class));
        binding.fileMapButton.setOnClickListener(v -> open(FileMapDemoActivity.class));
        binding.fileSetButton.setOnClickListener(v -> open(FileSetDemoActivity.class));
        binding.fileBytesButton.setOnClickListener(v -> open(FileBytesDemoActivity.class));
        binding.databaseIntButton.setOnClickListener(v -> open(DatabaseIntDemoActivity.class));
        binding.databaseLongButton.setOnClickListener(v -> open(DatabaseLongDemoActivity.class));
        binding.databaseFloatButton.setOnClickListener(v -> open(DatabaseFloatDemoActivity.class));
        binding.databaseDoubleButton.setOnClickListener(v -> open(DatabaseDoubleDemoActivity.class));
        binding.databaseBooleanButton.setOnClickListener(v -> open(DatabaseBooleanDemoActivity.class));
        binding.databaseStringButton.setOnClickListener(v -> open(DatabaseStringDemoActivity.class));
        binding.databaseObjectButton.setOnClickListener(v -> open(DatabaseObjectDemoActivity.class));
        binding.databaseListButton.setOnClickListener(v -> open(DatabaseListDemoActivity.class));
        binding.databaseMapButton.setOnClickListener(v -> open(DatabaseMapDemoActivity.class));
        binding.databaseSetButton.setOnClickListener(v -> open(DatabaseSetDemoActivity.class));
        binding.databaseBytesButton.setOnClickListener(v -> open(DatabaseBytesDemoActivity.class));
        binding.eventSendButton.setOnClickListener(v -> open(EventSendConfigRefreshedActivity.class));
        binding.eventReceiveButton.setOnClickListener(v -> open(EventReceiveRefreshRequestActivity.class));
    }

    private void open(Class<? extends Activity> page) {
        startActivity(new Intent(this, page));
    }
}
