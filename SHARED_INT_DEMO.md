# 最小演示：App1 写入操作计数 9999，App2 读取并监听

这个例子现在属于 [全类型案例目录](DEMO_CATALOG.md) 的 **MEMORY · int 操作计数**。两个 App 首页均可打开 `MemoryIntDemoActivity`；App1 写入，App2 读取和监听。

建议先看 [App1 的 XML](App1/app/src/main/res/layout/activity_memory_int_demo.xml) 和 [App2 的 XML](App2/app/src/main/res/layout/activity_memory_int_demo.xml)：`valueInput`、`initialButton`、`updateButton`、`readButton`、`listenButton` 和 `status` 都在 XML 中声明。然后看 [App1 Activity](App1/app/src/main/java/com/oem/globaldata/app1/MemoryIntDemoActivity.java) 与 [App2 Activity](App2/app/src/main/java/com/oem/globaldata/app2/MemoryIntDemoActivity.java) 如何调用生成的 `ActivityMemoryIntDemoBinding`。例如 `binding.valueInput.getText()` 取得输入，`binding.status` 交给状态显示方法；不需要 `findViewById`。

1. 在同一 Android 用户下安装并打开两个 `publicAppDebug` APK，确保用户已经解锁且镜像已集成 GlobalData 服务。
2. App1 首页 →「MEMORY · int 操作计数 · 写入」→「授权并写入 9999」。授权先完成，写入后显示 revision。
3. App2 首页 →「MEMORY · int 操作计数 · 读取/监听」。页面自动订阅并读取；应显示 `9999`。若先于授权打开，回到此页点击「开始或重试监听」。
4. 在 App1 输入新的 32 位整数并写入；保持 App2 页面在线，即可看到变化。

双方从 [DeviceDataKeys](globaldata-sdk/device-contracts/src/main/java/com/oem/globaldata/contracts/DeviceDataKeys.java) 使用同一个地址：

```java
DataKey<Integer> key = DeviceDataKeys.memoryIntOperationCount(BuildConfig.SYSTEM_SCOPE);
```

它对应 `MEMORY + USER_CE + App1 owner + share.com.oem.globaldata.app1 + operation_count + Codecs.INT`（普通变体）。平台签名变体改用 `DEVICE_DE + sys.device`。`operation_count` 表示业务上的操作计数，替代含义不清的旧 `a` Key；旧地址不再由案例使用。`owner` 是授权管理者，不表示 App2 无法读取。普通 App 的服务端权限仍根据真实 Binder UID、包和签名逐次验证。

App1 的关键代码是：

```java
manager.grantData(key, DeviceDataKeys.APP2_PACKAGE, Wire.READ | Wire.OBSERVE)
        .thenCompose(ignored -> manager.put(key, 9999));
```

App2 的关键代码是：

```java
manager.read(key); // 单独读取一次
manager.observeAndRead(key, getMainExecutor(), listener); // 订阅后立即读取当前值
```

App2 收到数据通知后，SDK 会再次读取当前值，并用 `Codecs.INT` 将服务端保存的四字节整数还原为 `Integer`。此例的值只在 `system_server` 内存中暂存；系统服务或设备重启后，App1 需要重新写入。没有定制系统设备时，以上跨 App 效果属于待设备验证项目。
