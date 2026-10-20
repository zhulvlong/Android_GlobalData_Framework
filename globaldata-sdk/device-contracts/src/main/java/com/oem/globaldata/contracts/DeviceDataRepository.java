package com.oem.globaldata.contracts;

import com.oem.globaldata.client.DataSnapshot;
import com.oem.globaldata.client.GlobalDataManager;
import com.oem.globaldata.client.WriteResult;
import java.util.concurrent.CompletableFuture;

/**
 * 用业务名称包装通用 SDK 操作的示例 Repository。
 * 它不维护第二份缓存：每次调用都交给 GlobalDataManager 与系统服务交互。
 */
public final class DeviceDataRepository {
    /** 负责 Binder 请求、Codec 编解码和连接恢复的 SDK 管理器。 */
    private final GlobalDataManager manager;
    /** 决定选用平台 DEVICE_DE/sys.* 或普通用户 USER_CE 的业务地址。 */
    private final boolean system;

    /**
     * 绑定业务地址类型；调用方仍负责在不用时关闭传入的 manager。
     *
     * @param manager 已创建的 SDK 管理器
     * @param system true 供受信任平台签名示例使用；普通 App 传 false
     */
    public DeviceDataRepository(GlobalDataManager manager, boolean system) {
        this.manager = manager;
        this.system = system;
    }

    /**
     * 异步写入 Memory Store 的 mode；服务端按调用 UID 检查地址权限。
     *
     * @param mode 要保存的模式整数
     * @return 异步写入结果；Memory 数据不跨 system_server 重启保留
     */
    public CompletableFuture<WriteResult> setMode(int mode) {
        return manager.put(DeviceDataKeys.memoryIntMode(system), mode);
    }

    /**
     * 异步读取当前 mode。
     *
     * @return 含状态、值与 revision 的快照 Future
     */
    public CompletableFuture<DataSnapshot<Integer>> mode() {
        return manager.read(DeviceDataKeys.memoryIntMode(system));
    }

    /**
     * 用 JSON Codec 异步写入文件后端，成功结果表示持久化写入已提交。
     *
     * @param config 待保存的配置
     * @return 成功时包含新 revision 的 Future；失败时异常完成
     */
    public CompletableFuture<WriteResult> saveFile(DeviceConfig config) {
        return manager.put(DeviceDataKeys.fileObjectConfig(system), config);
    }

    /**
     * 从文件后端异步读取并解码配置。
     *
     * @return 含配置对象、schema 与 revision 的快照 Future
     */
    public CompletableFuture<DataSnapshot<DeviceConfig>> file() {
        return manager.read(DeviceDataKeys.fileObjectConfig(system));
    }

    /**
     * 用同一 DeviceConfigCodec 把配置写入独立的 SQLite 后端。
     *
     * @param config 待保存的配置
     * @return 数据库提交后的写入结果 Future
     */
    public CompletableFuture<WriteResult> saveDatabase(DeviceConfig config) {
        return manager.put(DeviceDataKeys.databaseObjectConfig(system), config);
    }

    /**
     * 从 SQLite 后端异步读取配置，与文件中的同名 key 相互隔离。
     *
     * @return 含配置对象、schema 与 revision 的快照 Future
     */
    public CompletableFuture<DataSnapshot<DeviceConfig>> database() {
        return manager.read(DeviceDataKeys.databaseObjectConfig(system));
    }
}
