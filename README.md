# GlobalData Framework

面向定制 Android 16 系统的跨 App 数据托管示例工程。系统侧提供 Memory、File、Database 三种独立存储、数据变更通知和无载荷事件通道；App 通过独立发布的 SDK 访问。业务对象由 App 侧 Codec 编解码，系统服务只处理地址、权限、版本和字节值。

## 文档导航

| 文档 | 用途 |
|---|---|
| [当前架构说明](架构设计说明.md) | 按源码解释模块、存储、安全和验证边界 |
| [客户端调用指南](globaldata-sdk/客户端使用指南.md) | SDK API、授权、CAS、监听、事件与最小跨 App 示例 |
| [AOSP 接入与验证](aosp-patch/AOSP集成与验证指南.md) | 系统补丁放置、构建、签名和设备验收 |
| [演示页面目录](演示案例目录.md) | App1/App2 的 Key、Activity 与事件 |
| [SDK 工程说明](globaldata-sdk/README.md) | SDK 模块、构建和发布 |

## HTML 设计文档

- [v1 初稿](全局数据框架-v1初始设计.html) 保留原貌，用于追溯初始需求；其中的接口草案不代表当前代码。
- [最终 v2 架构设计](全局数据框架-v2最终架构设计.html) 合并原 v2.0 与 v2.1 中仍适用的内容，并按当前源码校正接口和部署边界。

## 工程与验证边界

- `aosp-patch/` 是待合入目标 AOSP/厂商源码的增量补丁，不是可独立启动的 Android 系统。
- `globaldata-sdk/` 是独立 Gradle 工程；`App1/`、`App2/` 是两个独立示例 App 工程。
- `verification/verify.ps1` 比较客户端和系统侧 IPC 声明与常量，并在主机 JVM 上检查 MemoryBackend 的 CAS 和隔离语义。

本地构建及主机测试不能代替系统镜像编译、平台签名、SELinux、重启、断电和跨用户设备测试。实际通过的检查以执行结果为准。
