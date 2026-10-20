# GlobalData SDK

此目录是独立 Android Studio/Gradle 工程。当前 Maven 版本为 `4.0.0`。逐接口说明和 App1/App2 最小示例见[客户端调用指南](客户端使用指南.md)；模块及系统边界见[当前架构说明](../架构设计说明.md)。

| 模块 | 职责 |
|---|---|
| `ipc` | `Wire` 常量及三个 AIDL 接口；必须与系统侧副本保持兼容。 |
| `client` | `GlobalDataManager`、三 Store 便利入口、Codec、CAS、数据监听及无载荷 EventBus。 |
| `device-contracts` | 示例 POJO、Codec、Key、Topic 和 Repository；业务项目可替换。 |
| `bridge-stubs` | 仅供编译公开 Bridge API；不能打入 APK。 |

构建配置为 API 36、Java 17、AGP 8.6.1；本工程 Wrapper 为 Gradle 8.7。App1/App2 分别使用 Gradle 8.13，并通过 composite build 引用同级 SDK。运行前将 `JAVA_HOME` 指向 JDK 17（当前终端若使用 JDK 11，AGP 会拒绝启动）。Windows PowerShell 中可依次运行：

```powershell
.\gradlew.bat :ipc:assembleDebug :client:assembleDebug :device-contracts:assembleDebug
.\gradlew.bat :client:testDebugUnitTest :device-contracts:testDebugUnitTest
.\gradlew.bat :ipc:publishToMavenLocal :client:publishToMavenLocal :device-contracts:publishToMavenLocal
```

发布后可依赖 `com.oem.globaldata:device-contracts:4.0.0`，其发布元数据传递依赖 `client` 与 `ipc`。新增业务对象、Codec、Key 或 Topic 时，重建 Contracts AAR 和使用它的 APK；改动 AIDL 或 `Wire` 需要同步 `aosp-patch` 并重新构建系统。2026-10-20 使用 JDK 17 的 Debug AAR 构建与 Client/Contracts 单元测试通过；构建器提示 AGP 8.6.1 对 API 36 未经当前版本正式支持。运行还需要目标设备镜像内真实的公开 Bridge 与 GlobalData 服务；本地 AAR/APK 编译不证明设备能力。
