package com.oem.globaldata.app2;

import android.app.Activity;
import android.os.Bundle;
import android.widget.TextView;
import com.oem.globaldata.client.DataSnapshot;
import com.oem.globaldata.client.GlobalDataManager;
import com.oem.globaldata.client.GlobalDataSdk;
import com.oem.globaldata.client.GlobalDataException;
import com.oem.globaldata.client.Subscription;
import com.oem.globaldata.ipc.Wire;
import java.util.concurrent.CompletableFuture;

/** 复用读端的订阅生命周期；各页面由自己的 Data Binding XML 提供控件。 */
abstract class DemoActivityBase extends Activity {
    protected GlobalDataManager manager;
    private TextView status;
    private Subscription first, second;
    private int firstRequest, secondRequest;

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        // 子类 inflate XML 并交给 bindStatus；基类不动态创建任何 View。
        manager = GlobalDataSdk.connect(this);
    }

    /** 直接使用生成的 binding.status，而不是按 ID 查找 View。 */
    protected void bindStatus(TextView status) { this.status = status; }

    protected void show(String action, DataSnapshot<?> snapshot) {
        onUi(() -> status.setText(action + "：" + (snapshot.present ? snapshot.value : "尚未写入")
                + "，revision=" + snapshot.revision));
    }

    protected void message(String value) { onUi(() -> status.setText(value)); }

    protected void report(String action, Object result, Throwable failure) {
        if (failure != null) error(action, failure);
        else message(action + "成功：" + result);
    }

    protected void error(String action, Throwable failure) {
        Throwable cause = failure;
        // Future 的包装异常可剥离，但保留 GlobalDataException 的状态码以区分系统缺失和未授权。
        while ((cause instanceof java.util.concurrent.CompletionException
                || cause instanceof java.util.concurrent.ExecutionException)
                && cause.getCause() != null) cause = cause.getCause();
        String detail = cause.getMessage();
        String hint = "";
        if (cause instanceof GlobalDataException) {
            int code = ((GlobalDataException) cause).status;
            if (code == Wire.STORAGE_UNAVAILABLE) hint = "。请检查定制镜像中的 GlobalData 服务与 Bridge。";
            else if (code == Wire.PERMISSION_DENIED) hint = "。请在 App1 对应页授权后重试。";
            detail = "status=" + code + "，" + detail;
        }
        String message = action + "失败：" + detail + hint;
        onUi(() -> status.setText(message));
    }

    private void onUi(Runnable update) {
        runOnUiThread(() -> { if (!isDestroyed()) update.run(); });
    }

    /** 重试监听时关掉同槽旧订阅，迟到的 Future 也不能覆盖新订阅。 */
    protected void keep(int slot, CompletableFuture<Subscription> pending) {
        int request;
        if (slot == 0) {
            request = ++firstRequest;
            if (first != null) first.close();
            first = null;
        } else {
            request = ++secondRequest;
            if (second != null) second.close();
            second = null;
        }
        pending.whenComplete((created, failure) -> runOnUiThread(() -> {
            boolean stale = isDestroyed() || request != (slot == 0 ? firstRequest : secondRequest);
            if (stale) { if (created != null) created.close(); return; }
            if (failure != null) { error("订阅", failure); return; }
            if (slot == 0) first = created; else second = created;
        }));
    }

    @Override public void onDestroy() {
        ++firstRequest;
        ++secondRequest;
        if (first != null) first.close();
        if (second != null) second.close();
        manager.close();
        super.onDestroy();
    }
}
