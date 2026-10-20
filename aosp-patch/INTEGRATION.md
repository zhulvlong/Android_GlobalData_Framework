# Android 16 AOSP 接入与验证

本目录是按 `android-16.0.0_r1` 设计的**增量源码**，不能整体覆盖 AOSP 的 `frameworks/base` 或 `system/sepolicy`。当前工作区没有系统源码，以下系统编译步骤必须在实际 AOSP/厂商 checkout 上运行，并依据该分支的 Soong/API/SELinux 诊断调整。

## 1. 源码放置

将 `aosp-patch/frameworks/base/core/java/android/globaldata/GlobalDataBridge.java` 放到 AOSP 同名路径。将 `aosp-patch/frameworks/base/services/core/java/com/android/server/globaldata/` 和 `.../com/oem/globaldata/ipc/` 两个目录放到 AOSP 同名路径。若工作区与 AOSP checkout 位于同一台 Linux 主机，可在 AOSP 根目录执行以下复制命令（先将引号中的示例路径换成真实绝对路径）：

```sh
export GD_PATCH="/absolute/path/to/Android_GlobalData_Framework/aosp-patch"
mkdir -p frameworks/base/core/java/android/globaldata
mkdir -p frameworks/base/services/core/java/com/android/server/globaldata
mkdir -p frameworks/base/services/core/java/com/oem/globaldata/ipc
cp "$GD_PATCH/frameworks/base/core/java/android/globaldata/GlobalDataBridge.java" frameworks/base/core/java/android/globaldata/
cp "$GD_PATCH"/frameworks/base/services/core/java/com/android/server/globaldata/*.java frameworks/base/services/core/java/com/android/server/globaldata/
cp "$GD_PATCH"/frameworks/base/services/core/java/com/oem/globaldata/ipc/* frameworks/base/services/core/java/com/oem/globaldata/ipc/
```

三个 AIDL 文件和 `Wire.java` 与 `globaldata-sdk/ipc` 保持完全相同的包名、字段名、数值和方法顺序；改变协议必须协商 major/minor 版本。复制后可在 Windows 工程目录运行 `verification/verify.ps1` 检查交付源码一致性和 MemoryBackend 的 CAS 语义。

Android 16 的 `services.core-sources` 通配符收集新的 `.java`。在 `frameworks/base/services/core/Android.bp` 的 `services.core-aidl-sources` filegroup 的 `srcs` 追加：

```bp
"java/com/oem/globaldata/ipc/IGlobalDataTransport.aidl",
"java/com/oem/globaldata/ipc/IDataObserver.aidl",
"java/com/oem/globaldata/ipc/IEventListener.aidl",
```

在 `frameworks/base/services/java/com/android/server/SystemServer.java` 导入 `com.android.server.globaldata.GlobalDataSystemService`，并在 `startOtherServices()` 中系统服务初始化完成、普通 App 可启动之前加入：

```java
mSystemServiceManager.startService(GlobalDataSystemService.class);
```

本实现采用公开静态 Bridge 获取 Binder，**不需要**向 `Context` 或 `SystemServiceRegistry` 注册新的 manager。Bridge 只提供 `IBinder`，不会把 Client SDK 或业务类放入 bootclasspath；真实访问控制在每次 Binder 调用中执行。

## 2. 公开 API 与 SELinux

`GlobalDataBridge.getTransportBinder()` 必须出现在 OEM 公开 SDK API 中。按目标分支的 API 更新任务（AOSP 16 通常执行 `m update-api`）更新 `frameworks/base/api/current.txt` 及相关 API 文件；不要给公开 Bridge 添加 `@hide`，不要让普通 App 反射调用隐藏的 `ServiceManager`。SDK 工程中的 `bridge-stubs` 是编译期临时桩，运行时**不能**打入 APK；平台构建后应将其与真实 OEM 公开 SDK 桩对照，方法签名一致后再发布。

`aosp-patch/system/sepolicy/` 中的五个 `.fragment` 文件分别追加到同名策略文件的相应位置：`public/service.te`、`private/system_server.te`、`private/file.te`、`private/file_contexts`、`private/service_contexts`。它们不是可直接覆盖系统策略文件的完整文件。复制时跳过 `.fragment` 中的说明注释。`app_api_service` 允许普通应用发现该 Binder，但服务仍逐操作校验 UID、包名、签名、作用域和 ACL。DE/CE 数据目录只授权 `system_server` 文件读写，不给 App 直接文件权限。新建数据目录时服务会调用 `SELinux.restoreconRecursive`；已有目录可在调试设备上执行 `adb shell restorecon -RF /data/system_de/0/globaldata`，并对用到的 CE User 目录同样执行。厂商分支如果将这些路径放在设备策略目录，按其 sepolicy 分区和 neverallow 诊断移植，不要关闭 SELinux enforcing。

## 3. 系统编译与部署

在 Linux AOSP 根目录：

```sh
source build/envsetup.sh
lunch <目标设备产品>-userdebug
m update-api
m framework-minus-apex services
m
```

先解决 API/Soong 编译，再解决 sepolicy 编译；保留每次失败日志和修改记录。上述命令只规定顺序，`lunch` 产品名、系统镜像刷入方式和厂商补丁位置由实际目标源码决定。构建成功后按设备官方刷机流程安装生成的 `system.img`、`system_ext.img` 等镜像；仅推送 `services.jar` 或 `framework.jar` 不足以同步公开 API 与 SELinux。镜像部署后先用 `adb shell service list | grep globaldata` 确认服务注册，再查看 `adb logcat` 中的 `GlobalData`/SELinux 拒绝信息。没有该 AOSP checkout 时，不能声明系统源码已无错编译；取得目标源码或失败日志后仍需按实际分支修改到编译通过。

## 4. 本地 SDK 与 APK

三个独立 Android Studio 项目位于 `globaldata-sdk/`、`App1/`、`App2/`。SDK 包含 `ipc`、`client`、`device-contracts` 三个发布单元，`bridge-stubs` 只供编译。App1/App2 通过 Gradle composite build 引用同级 SDK 工程；脱离同级源码构建时先将三个 AAR 发布到 Maven 仓库，移除 `includeBuild('../globaldata-sdk')`，在 `settings.gradle` 加 `mavenLocal()` 或自己的 Maven 仓库。构建参数：API 36、AGP 8.13.1、Gradle 8.13、JDK 17。

```powershell
cd globaldata-sdk; .\gradlew.bat :ipc:assembleDebug :client:assembleDebug :device-contracts:assembleDebug
.\gradlew.bat :client:testDebugUnitTest :device-contracts:testDebugUnitTest
.\gradlew.bat :ipc:publishToMavenLocal :client:publishToMavenLocal :device-contracts:publishToMavenLocal
cd ..\App1; .\gradlew.bat :app:assemblePublicAppDebug :app:assemblePlatformAppRelease
cd ..\App2; .\gradlew.bat :app:assemblePublicAppDebug :app:assemblePlatformAppRelease
cd ..; .\verification\verify.ps1
```

普通变体：先安装 App2，再安装 App1；App1 点击“授权 App2”，随后双方可操作 `USER_CE` 的 `share.com.oem.globaldata.app1`。平台变体用于 `DEVICE_DE` 的 `sys.device`。**PlatformAppDebug 默认调试签名不具备平台身份**；需要平台签名私钥后，用 `PLATFORM_KEYSTORE`、`PLATFORM_STORE_PASSWORD`、`PLATFORM_KEY_ALIAS`、`PLATFORM_KEY_PASSWORD` 提供 JKS/PKCS12 签名，或对 unsigned release APK 使用目标 ROM 的签名流程。不要把平台私钥提交进本工程。同一包名的普通/平台变体不能并存安装，切换前卸载旧变体。

## 5. 设备验收顺序

1. App2 未获授权时读/订阅失败；App1 授权后能读、写、监听，撤销后新读写和回调均被拒绝。
2. 三 Store 同名 key 互不覆盖；File/DB 成功写入后重启仍可读，Memory 重启后为空。
3. 两 App 并发 CAS：旧 revision 冲突；删除后旧 revision 不能写回。模拟文件写入/SQLite 失败时无成功通知。
4. 事件只到在线授权订阅者；离线无补发，三 Store 不新增事件 Key。
5. 未解锁访问 CE 返回 `LOCKED`；跨 User/Profile 无默认授权；UID 复用和签名变更不取得旧 owner 权限。
6. 新增 DeviceConfig 字段和 Topic 时，仅重建 Contracts AAR 与相关 APK；确认系统镜像二进制不变。

本地只能验证 SDK/App 构建和可脱离系统的单元逻辑；重启、签名权限、CE/DE、SELinux、断电与系统服务恢复必须在刷入目标镜像的设备上完成。
