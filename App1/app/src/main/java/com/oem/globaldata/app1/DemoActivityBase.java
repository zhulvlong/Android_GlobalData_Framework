package com.oem.globaldata.app1;

import android.app.Activity;
import android.os.Bundle;
import android.widget.TextView;
import com.oem.globaldata.client.GlobalDataManager;
import com.oem.globaldata.client.GlobalDataSdk;

/** 只复用客户端生命周期和状态显示；控件均在各 Activity 的 XML 中声明。 */
abstract class DemoActivityBase extends Activity {
    protected GlobalDataManager manager;
    private TextView status;

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        // 本类不创建 View。子类 inflate 自己的 Data Binding 布局，再绑定状态控件。
        manager = GlobalDataSdk.connect(this);
    }

    /** 传入 Data Binding 生成的控件引用，无需 findViewById。 */
    protected void bindStatus(TextView status) { this.status = status; }

    protected void report(String action, Object result, Throwable error) {
        runOnUiThread(() -> {
            if (isDestroyed()) return;
            status.setText(error == null ? action + "成功：" + result
                    : action + "失败：" + cause(error).getMessage());
        });
    }

    protected void invalid(String message) { status.setText(message); }

    private static Throwable cause(Throwable error) {
        while (error.getCause() != null) error = error.getCause();
        return error;
    }

    @Override public void onDestroy() {
        manager.close();
        super.onDestroy();
    }
}
