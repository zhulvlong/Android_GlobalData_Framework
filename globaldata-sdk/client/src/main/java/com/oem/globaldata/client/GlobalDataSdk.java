package com.oem.globaldata.client;

import android.content.Context;

/** 客户端入口，只负责建立 manager；Binder 真正连接发生在首次操作时。 */
public final class GlobalDataSdk {
    /** 工具类不创建实例。 */
    private GlobalDataSdk() {}
    /**
     * 使用 Application Context 创建客户端，避免页面 Context 被后台订阅长期持有。
     * @param context 任意可用的 Android Context
     * @return 需在业务生命周期结束时 close 的 GlobalDataManager
     */
    public static GlobalDataManager connect(Context context) {
        return new GlobalDataManager(context.getApplicationContext());
    }
}
