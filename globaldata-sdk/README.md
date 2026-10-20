# GlobalData SDK 4.0.0

完整的逐接口调用、权限、调用链与服务端存储说明见 [客户端接口调用指南](CLIENT_API_GUIDE.md)。
两个 App 的 33 类数据与双向无载荷事件独立页面见 [演示目录](../DEMO_CATALOG.md)。

此工程可单独由 Android Studio 打开。`ipc` 固定 AIDL/Wire 协议，`client` 提供通用 `GlobalDataManager`、三 Store、`DataKey<T>`、`ValueCodec<T>`、CAS、监听及 EventBus；`device-contracts` 是可由业务团队独立发布的对象/Key/Topic/Repository 示例。`bridge-stubs` 仅供编译，不能作为运行时依赖打入 APK。

在 Android SDK API 36、JDK 17 下运行：

```powershell
.\gradlew.bat :ipc:assembleDebug :client:assembleDebug :device-contracts:assembleDebug
.\gradlew.bat :client:testDebugUnitTest :device-contracts:testDebugUnitTest
.\gradlew.bat :ipc:publishToMavenLocal :client:publishToMavenLocal :device-contracts:publishToMavenLocal
```

如果 SDK 的 Gradle Wrapper 首次运行要下载 Gradle 8.7，请保持网络可用，或在 Android Studio 中指定本地 Gradle 8.7。AAR 位于各模块的 `build/outputs/aar/`；发布后 Maven 坐标为 `com.oem.globaldata:device-contracts:4.0.0`，通过 POM 传递依赖 `client` 和 `ipc`。在独立 App 工程的仓库列表加入 `mavenLocal()`，再依赖该坐标。当前 App1/App2 使用同级工程的 composite build，开发时能直接看到 Contracts 的改动。

`DataKey<T>` 固定 Store/Scope/owner/namespace/key/codec。`Codecs` 提供 int、long、float、double、boolean、String 与 byte[]；`DeviceConfigCodec` 演示旧版 schema 读取与新版写入；`DeviceCollections` 为 String、Integer、Long、Float、Double、Boolean 的 List、Set 及 String 键 Map 分别提供明确的 Codec。原 String 集合保持 JSON 格式；新增 15 种格式使用版本 1 的长度前缀二进制，浮点沿用单值 Codec 的 IEEE 754 位语义。只需新增业务对象、Codec、Key/Topic、Repository 并重建 Contracts AAR 与用到它的 APK，无需改系统协议或重刷系统。修改 AIDL 或 `Wire` 则必须同步更新 `aosp-patch` 并重新构建系统。

`DeviceDataKeys` 的工厂名按 Store、数据格式、业务含义排列，例如 `memoryIntOperationCount`、`fileObjectDeviceConfig`、`databaseMapStringIntModuleRetryCounts`；Map 名中的 `StringInt` 表示 `Map<String,Integer>`。3.0.0 移除了旧工厂名，调用方需改名并重新编译。地址和 Codec 未改，已有设备数据无需迁移。

`DeviceTopics` 集中定义无载荷事件地址。4.0.0 将方法名改为“发送方→接收方＋业务事件”，例如 `app1ToApp2ConfigRefreshedEvent` 和 `app2ToApp1ConfigRefreshRequestEvent`；早期通用事件改名为 `legacyConfigRefreshEvent`。旧方法名已移除，调用方需改名并重新编译。事件的 scope、owner、topic 字符串未改，无须更新系统服务。

写入使用 `put`、`putIfAbsent` 或 `putIfRevision(key, value, snapshot)`；删除可用 `remove` 或 `removeIfRevision(key, snapshot)`。CAS 要传读取时的完整快照，因为 epoch 会在系统服务重启后变化。`observeAndRead` 首次订阅后主动读取，服务重启会提示重同步并恢复订阅；回调按订阅顺序在调用方提供的 Executor 上执行。关闭页面时关闭 `Subscription` 和 `GlobalDataManager`。
