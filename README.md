# GlobalData Framework

Android 16 系统级跨应用数据托管示例实现。设计依据为目录中的三份 HTML 文档，代码以 v2.1 的职责边界为准：初稿的 Memory、File、SQLite 三种独立存储，v2.0 的无载荷 EventBus，以及 v2.1 的小型系统 Bridge、独立 SDK、业务 Contracts 和 owner 授权模型。

普通 App 不直接访问系统数据文件；它经公开 Bridge 取得 Binder，服务端按真实调用 UID、包名、签名、User 和资源权限逐次检查。单值上限为 64 KiB。File/SQLite 在提交成功后才返回成功及发送通知；Memory 随 `system_server` 重启清空。EventBus 不落盘也不补发。CAS 同时比较 revision 与服务 epoch，删除后保留版本高水位。

工程目录：

- `aosp-patch/`：拟合入 `android-16.0.0_r1` 的系统侧源码与集成说明。
- `globaldata-sdk/`：独立 Android Studio 工程，构建 IPC、通用 Client 与业务 Contracts AAR。
- `App1/`、`App2/`：两个独立 Android Studio 测试工程；每个演示页使用独立 XML 布局和生成的 Data Binding 类。

入门可先看 [App1 写入 int、App2 读取并监听的最小演示](SHARED_INT_DEMO.md)，再从 [33 类数据与双向事件页面目录](DEMO_CATALOG.md) 进入各独立 Activity。接口、系统接入和构建说明分别见 [客户端接口调用指南](globaldata-sdk/CLIENT_API_GUIDE.md)、[集成与验证说明](aosp-patch/INTEGRATION.md) 和 [SDK 使用说明](globaldata-sdk/README.md)。没有目标 AOSP 源码和平台签名密钥时，系统镜像编译、平台签名运行和设备测试无法在本机完成；SDK 与普通 APK 可独立编译。
