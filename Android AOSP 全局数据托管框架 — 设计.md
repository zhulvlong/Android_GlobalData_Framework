# Android AOSP 全局数据托管框架 — 重新设计

这次理解了你的完整需求。你希望开发一套 Android 系统级全局数据托管框架（GlobalData Framework），核心不是简单地把 SharedPreferences 跨进程共享，而是让 `GlobalDataService` 为所有 App 统一托管三种不同生命周期、不同存储介质的数据。

三种存储方式必须同时存在，彼此独立，但使用方式尽可能一致：

1. 内存托管存储（MemoryStore）：只保存到系统服务内存中，正常开机期间可以被多个 App 读写和监听，设备重启后数据消失。
2. 文件托管存储（FileStore）：类似系统级 SharedPreferences，数据持久化到文件，设备重启后可以继续读取。
3. 数据库托管存储（DatabaseStore）：数据持久化到 SQLite，设备重启后可以继续读取，适合更多数据和频繁修改。

三种方式都必须支持：

创建 / 赋值、读取、修改、删除、基本类型、Java 引用类型、跨进程数据变更通知。

而且必须满足最重要的扩展性要求：

> App 新增一个 `int`、`boolean`、`String`、`List`、`Map` 或任意可序列化 Java 类的数据，只修改 Apps 层代码，不修改 GlobalDataManager、AIDL、GlobalDataService，不重新编译 AOSP。

这完全可以实现。

## 一、最终推荐架构

### Apps 业务层

普通 App

Settings

Launcher / SystemUI

Framework

只负责定义数据、选择存储、赋值、读取、修改、监听

### GlobalDataManager.java

统一客户端 SDK · 一次实现，业务新增时不修改

MemoryStore API

FileStore API

DatabaseStore API

统一类型编解码 · put / get / update / remove / observe

Binder IPC / AIDL

## GlobalDataService

AOSP system_server 中的系统级托管服务

权限及命名空间管理

存储路由 StorageRouter

原子读写与版本管理

变更通知 ObserverManager

根据 App 选择路由

MemoryBackend

内存 Map

重启丢失

FileBackend

AtomicFile / Proto

永久保存

DatabaseBackend

SQLite / WAL

永久保存

这次架构的关键变化是：

GlobalDataService 不直接把所有数据都写进 SQLite，而是增加 `StorageRouter` 和三种独立的 `StorageBackend`。

Apps 层明确选择要使用的存储方式，GlobalDataService 统一管理整个数据生命周期。

## 二、三种托管存储的定义

| 功能               | MemoryStore  | FileStore      | DatabaseStore      |
| ------------------ | ------------ | -------------- | ------------------ |
| 基本类型           | 支持         | 支持           | 支持               |
| Java 类对象        | 支持         | 支持           | 支持               |
| List / Map         | 支持         | 支持           | 支持               |
| 创建 / 赋值        | 支持         | 支持           | 支持               |
| 读取 / 修改 / 删除 | 支持         | 支持           | 支持               |
| 跨 App 数据共享    | 支持         | 支持           | 支持               |
| 跨进程变更通知     | 支持         | 支持           | 支持               |
| 应用退出后存在     | 是           | 是             | 是                 |
| 设备重启后存在     | 否           | 是             | 是                 |
| 数据存储位置       | 服务端内存   | 系统托管文件   | 系统 SQLite        |
| 主要场景           | 实时运行状态 | 少量配置、对象 | 大量记录、高频更新 |

内存托管有一项必须明确的生命周期边界：MemoryStore 的实际生命周期是 GlobalDataService 所在进程的生命周期。 正常运行时可以跨 App 进程死亡继续存在，但如果 `system_server` 异常重启，内存值也会丢失。设备重启后则一定不会从旧内存中恢复。

所以，你描述的“开机到关机”可以作为通常情况下的使用语义，但不能将它理解为在任何系统服务异常重启后都保证不丢失。

## 三、Apps 层最终应该如何使用？

我建议不要把三种存储功能散落成大量不同的方法，例如 `putMemoryInt()`、`putFileInt()`、`putDatabaseInt()`。

最好由 GlobalDataManager 提供三个存储入口，返回统一的 `GlobalStore` 接口。

### 3.1 获取三个托管存储对象

```
GlobalDataManager manager =
        context.getSystemService(GlobalDataManager.class);

// ① 内存托管
GlobalStore memory = manager.memory("device");

// ② 文件托管
GlobalStore file = manager.file("device");

// ③ SQLite 数据库托管
GlobalStore database = manager.database("device");
```

`device` 是一个 namespace，代表数据逻辑分组，并不是物理文件名。

这三个对象具有相同的基本操作方法。

### 3.2 内存托管：基本数据类型

假设 Settings 需要创建一个全局实时运行状态：

```
GlobalStore memory = manager.memory("device");

// 创建并赋值
memory.putInt("device_mode", 1);
memory.putBoolean("network_ready", true);
memory.putString("current_page", "home");

// 读取
int mode = memory.getInt("device_mode", 0);

// 修改
memory.putInt("device_mode", 2);

// 删除
memory.remove("device_mode");
```

在其他 App，例如 Launcher 中：

```
GlobalStore memory = manager.memory("device");

int mode = memory.getInt("device_mode", 0);
```

读取到的是同一份 GlobalDataService 托管的数据，不是 Launcher 自己的静态变量。

### 3.3 文件托管：基本数据类型

```
GlobalStore file = manager.file("device");

// 创建并持久化
file.putInt("brightness_default", 80);
file.putBoolean("boot_animation", true);

// 读取
int brightness =
        file.getInt("brightness_default", 50);

// 修改并持久化
file.putInt("brightness_default", 90);
```

调用成功后，数据已经完成相应的持久化提交。下次设备开机后，可以直接读取：

```
GlobalStore file = manager.file("device");

int brightness =
        file.getInt("brightness_default", 50);
```

App 不需要知道数据写入的是 XML、ProtoBuf 还是其他文件格式。

### 3.4 数据库托管：基本数据类型

```
GlobalStore database = manager.database("device");

// 创建并持久化到 SQLite
database.putInt("device_count", 100);

// 读取
int count = database.getInt("device_count", 0);

// 修改
database.putInt("device_count", 101);
```

与 FileStore 完全一样的操作方式，但底层是 SQLite。

以上三组代码是建议实现的 SDK 接口设计，并非 AOSP 现有 API。

这样开发人员只需要选择：

```
manager.memory(namespace)
manager.file(namespace)
manager.database(namespace)
```

后面所有基本操作完全一致。

## 四、Java 引用类型对象如何统一托管？

这是整个架构最重要的技术设计之一。

假设 App A 自己定义：

```
public class DeviceConfig {
    public int deviceId;
    public String deviceName;
    public boolean enabled;
}
```

你希望这个对象能够分别保存到内存、文件、数据库，而且 GlobalDataService 完全不知道 `DeviceConfig` 是什么类。

解决办法是：Apps 层负责对象序列化，SDK 负责统一封装，GlobalDataService 只负责托管序列化后的数据。

### 4.1 定义统一的对象编解码接口

在 SDK 中一次性定义：

```
public interface ValueCodec<T> {

    String schemaId();

    int schemaVersion();

    byte[] encode(T value);

    T decode(byte[] data, int savedVersion);
}
```

App 可以采用 JSON、ProtoBuf 等序列化方式。

例如使用 Gson，App 自己实现：

```
ValueCodec<DeviceConfig> codec =
        new ValueCodec<DeviceConfig>() {

    private final Gson gson = new Gson();

    @Override
    public String schemaId() {
        return "example.device_config";
    }

    @Override
    public int schemaVersion() {
        return 1;
    }

    @Override
    public byte[] encode(DeviceConfig value) {
        return gson.toJson(value).getBytes(
                StandardCharsets.UTF_8);
    }

    @Override
    public DeviceConfig decode(byte[] data, int version) {
        String json = new String(
                data, StandardCharsets.UTF_8);

        return gson.fromJson(json, DeviceConfig.class);
    }
};
```

`schemaId` 和 `schemaVersion` 用于标识数据格式以及后续版本迁移。另一个 App 想读取这个对象，就必须了解相同的数据结构与编解码协议。

### 4.2 内存托管对象

```
GlobalStore memory = manager.memory("device");

DeviceConfig config = new DeviceConfig();
config.deviceId = 1001;
config.deviceName = "Android TV";
config.enabled = true;

// 创建并赋值
memory.putObject("runtime_config", config, codec);

// 读取
DeviceConfig saved = memory.getObject(
        "runtime_config", codec);

// 修改并重新赋值
saved.enabled = false;

memory.putObject("runtime_config", saved, codec);
```

上述对象的数据只保存在 GlobalDataService 的内存中。

注意，服务端保存的是对象序列化后的值快照，不是 App 进程中的 Java 对象引用。

因此，执行：

```
saved.enabled = false;
```

不会自动通知其他进程。必须通过 `putObject()` 或下面的 `updateObject()` 提交修改。

### 4.3 文件托管对象

```
GlobalStore file = manager.file("device");

file.putObject("saved_config", config, codec);

DeviceConfig saved = file.getObject(
        "saved_config", codec);
```

文件写入由 GlobalDataService 完成。

设备重启后，App 不需要重新创建 `saved_config`，直接 `getObject()` 即可读取之前保存的对象。

### 4.4 数据库托管对象

```
GlobalStore database = manager.database("device");

database.putObject("db_config", config, codec);

DeviceConfig saved = database.getObject(
        "db_config", codec);
```

三种方式使用相同的对象 API，区别只在于获取的是哪个 `GlobalStore`。

### 4.5 建议支持原子修改对象

比起每次手动 `getObject()` 后再 `putObject()`，SDK 可以进一步封装：

```
memory.updateObject(
        "runtime_config",
        codec,
        old -> {
            old.enabled = false;
            return old;
        }
);
```

这个 `updateObject()` 不能只是简单的读取再覆盖，否则两个 App 同时修改对象可能互相丢失更新。

正确实现是：

1. SDK 读取对象和 revision。
2. 在客户端反序列化、执行修改函数。
3. SDK 使用 CAS（比较版本再写入）提交新对象。
4. 如果 revision 已被其他进程修改，就重新读取并重试。
5. 写入成功后由服务端通知其他 App。

这套逻辑应该在 SDK 中一次性实现，所有业务类都能复用，不需要每个 App 自己处理并发。

## 五、三种存储共享同一个变更通知系统

我建议监听接口也完全统一。

### 内存对象监听

```
GlobalStore memory = manager.memory("device");

Subscription subscription = memory.observe(
        "runtime_config",
        context.getMainExecutor(),
        event -> {
            DeviceConfig updated = memory.getObject(
                    "runtime_config", codec);

            updateUi(updated);
        }
);
```

### 文件数据监听

```
GlobalStore file = manager.file("device");

Subscription subscription = file.observe(
        "saved_config",
        context.getMainExecutor(),
        event -> {
            DeviceConfig config = file.getObject(
                    "saved_config", codec);

            updateUi(config);
        }
);
```

### SQLite 数据监听

```
GlobalStore database = manager.database("device");

Subscription subscription = database.observe(
        "db_config",
        context.getMainExecutor(),
        event -> {
            DeviceConfig config = database.getObject(
                    "db_config", codec);

            updateUi(config);
        }
);
```

这里的 `Subscription` 应实现 `close()`，让 App 在不需要监听时注销回调。上面所有示例仍是目标 SDK API，而非当前已经实现的代码。

### 通知机制设计

每一条变化事件建议包含以下信息：

| 字段        | 含义                           |
| ----------- | ------------------------------ |
| `storeType` | MEMORY / FILE / DATABASE       |
| `namespace` | 数据命名空间                   |
| `key`       | 被修改的数据标识               |
| `operation` | CREATE / UPDATE / DELETE       |
| `revision`  | 数据的新版本号                 |
| `epoch`     | 服务运行实例标识，用于重连判断 |

App A 修改某个全局对象

GlobalDataManager → Binder → GlobalDataService

MemoryBackend 更新成功

FileBackend 提交成功

DatabaseBackend 事务成功

ObserverManager 发布变更事件

App B

Launcher

SystemUI

Android 可以通过 AIDL 回调与 `RemoteCallbackList` 管理这类跨进程监听，并在监听客户端死亡后清理注册信息。

![img](https://www.google.com/s2/favicons?domain=https://developer.android.com&sz=32)

Android Developers



需要注意，数据监听不是持久化消息队列。App 没有运行时不会自动保存通知供下次补发，所以每个 App 启动时应读取当前数据，并在 Binder 重新连接后重新注册监听。

## 六、GlobalDataService 的内部详细架构

我建议将 GlobalDataService 分成六个核心组件，避免把所有逻辑写在一个巨大的 Java 类中。

```
GlobalDataService
│
├── GlobalDataBinder
│    └── 统一 AIDL IPC 入口
│
├── AccessController
│    └── UID / 用户 / Namespace 权限检查
│
├── StorageRouter
│    ├── MEMORY   → MemoryBackend
│    ├── FILE     → FileBackend
│    └── DATABASE → DatabaseBackend
│
├── StorageBackend (统一接口)
│    ├── get()
│    ├── put()
│    ├── putIfRevision()
│    ├── remove()
│    └── getRevision()
│
├── RevisionManager
│    └── 版本控制与并发冲突检查
│
└── ObserverManager
     ├── register()
     ├── unregister()
     └── dispatchChange()
```

### 6.1 三种 Backend 实现

MemoryBackend.java

基于 `ConcurrentHashMap` 或分段锁 Map 保存键值和版本信息。读写都在服务端内存中完成，不涉及磁盘。为了真正实现原子更新，版本检查和数据替换必须作为同一个操作执行。

不建议把任意 App 传来的 Java 实例直接放入 `system_server`，应保存字节快照并控制内存配额。

FileBackend.java

由服务端使用 `AtomicFile` 配合自定义二进制格式或 ProtoBuf 保存一个 namespace 下的多个键值，行为类似 SharedPreferences，但数据归 GlobalDataService 所有。

`AtomicFile` 可以帮助避免写入中断造成半成品文件，但并不提供自动并发锁，因此仍要由服务端串行化写入。

![img](https://www.google.com/s2/favicons?domain=https://developer.android.com&sz=32)

Android Developers



文件存储适合数据量较少、读取频繁、写入不频繁的配置。

DatabaseBackend.java

使用 SQLite + WAL，建立通用 KV 表，把基本类型和类对象统一保存成带类型标识的 BLOB。

SQLite 适合数据量较大、频繁写入以及需要事务的场景。第一版可以先实现与另外两个 Backend 一致的 KV 接口；如果以后需要按字段查询对象、过滤、排序和多表关联，再增加数据库专用的 Collection / Query 接口。

### 6.2 定义 Backend 统一接口

这个接口在 Service 内部实现，Apps 不需要接触。

```
public interface StorageBackend {

    DataEntry get(StoreKey key);

    long put(StoreKey key, DataEntry value,
             long expectedRevision);

    long remove(StoreKey key,
                long expectedRevision);

    long getRevision(StoreKey key);
}
```

数据唯一身份：

```
StoreKey = {
    storageType,
    userScope,
    namespace,
    key
}
```

数据内容：

```
DataEntry = {
    valueType,
    schemaId,
    schemaVersion,
    payload,
    revision
}
```

例如同样是 `device_mode`：

```
memory.putInt("device_mode", 1);
file.putInt("device_mode", 2);
database.putInt("device_mode", 3);
```

它们是三个互不干扰的数据对象，读取结果分别是 `1`、`2`、`3`。

也就是说，三种存储是独立的，而不是在不同介质上自动保持一份数据同步。

如果以后需要“内存修改后自动同步文件”，可以额外增加镜像存储策略，但我不建议在 V1 将它混入三种明确的存储语义。

### 6.3 统一 AIDL 接口

实际上，底层 AIDL 不必为每一种数据类型、每一种存储方式分别提供一个方法。

可以将复杂性全部放在客户端 SDK。

例如内部协议：

```
interface IGlobalDataService {

    DataEntry get(
            int storageType,
            String namespace,
            String key);

    long put(
            int storageType,
            String namespace,
            String key,
            in DataEntry value,
            long expectedRevision);

    long remove(
            int storageType,
            String namespace,
            String key,
            long expectedRevision);

    void registerObserver(
            int storageType,
            String namespace,
            String key,
            IGlobalDataObserver observer);

    void unregisterObserver(
            IGlobalDataObserver observer);
}
```

这是核心协议草案。实际 AIDL 文件还需定义 `DataEntry` 的 Parcelable 契约，以及用户范围等元数据。

它可以处理所有三种存储及所有数据类型，因为 Binder 传输的是数据描述与字节内容，而不是业务 Java 类。AOSP 官方也推荐将 AIDL 用于内部跨进程通信，由 Framework SDK 对外提供应用可使用的 API。

![img](https://www.google.com/s2/favicons?domain=https://source.android.com&sz=32)

Android Open Source Project



## 七、系统生命周期与数据恢复

这是你的内存托管与持久化托管之间最重要的区别。

阶段 1

第一次开机

阶段 2

App 写入

阶段 3

关机 / 重启

阶段 4

第二次开机

MemoryStore

丢失

空

存储值 A

内存释放

空

FileStore

保留

读取文件

写入值 B

文件保留

读取值 B

DatabaseStore

保留

打开 SQLite

写入值 C

数据库保留

读取值 C

系统启动时，GlobalDataService 只需要初始化各 Backend。

MemoryBackend 创建空内存容器。FileBackend 在首次访问时加载已保存的文件，或者根据实际性能需求提前加载。DatabaseBackend 打开 SQLite，按 Key 查询已存储的数据。

这里不需要 Apps 层主动去做持久化数据恢复。

持久化数据还要区分加密存储范围：需要开机未解锁即可访问的设备配置使用 DE 存储，涉及用户隐私的配置优先使用 CE 存储。AOSP 的文件级加密机制明确区分了这两种访问阶段。

![img](https://www.google.com/s2/favicons?domain=https://source.android.com&sz=32)

Android Open Source Project



## 八、实际量产必须提前确定的约束

| 设计问题     | 建议约束                                           |
| ------------ | -------------------------------------------------- |
| 跨 App 权限  | 由服务端按 UID、用户、namespace、应用身份控制      |
| 新建数据     | `put()` 自动创建，`putIfAbsent()` 可选             |
| 并发修改     | 基于 revision 的 CAS，避免丢更新                   |
| 数据变更监听 | 按 storageType + namespace + key 精确订阅          |
| 自定义对象   | 使用 Codec 序列化，不在服务端反射业务类            |
| 对象版本升级 | 使用 schemaId、schemaVersion 与迁移逻辑            |
| 大数据       | 限制普通 Binder 单次数据大小，必要时使用文件描述符 |
| 内存占用     | 按 UID、namespace 设置配额                         |
| 持久化可靠性 | 同步提交成功后再通知                               |
| 服务异常     | SDK 重连、重新注册 Observer、重新同步数据          |

特别是两点：

第一：权限管理本身也要通用化。 可以由 App 注册自己拥有的 namespace，并在授权规则允许时授予其他 App 读写权限。不能每新增一个全局 Key，都要求修改 AOSP 的权限代码。普通第三方 App 也不能随意使用 `sys` 等受保护 namespace。

第二：不能让大对象直接无限制进入 system_server。 Android Binder 的事务缓冲区有大小限制，当前常见实现中为进程级共享的约 1 MB 缓冲区。建议 V1 将单个值上限设为 64 KB 左右，并支持后续扩展大对象传输机制。

![img](https://www.google.com/s2/favicons?domain=https://developer.android.com&sz=32)

Android Developers



此外，大量磁盘写入应在服务端专用 I/O 执行环境中串行处理，不能阻塞 `system_server` 主线程。对于大量不受信任普通 App 同时使用的产品，甚至可以进一步将数据存储引擎移到独立系统进程，以降低存储故障对整个系统的影响，而对外 SDK 不需要变化。

## 九、最终确定的 GlobalDataManager V1 架构

## GlobalDataManager V1

建议冻结的模块与接口职责

| 客户端 SDK     | 提供 `memory()`、`file()`、`database()` 三种入口 |
| -------------- | ------------------------------------------------ |
| 统一操作       | `put / get / update / remove / observe`          |
| 统一数据类型   | 基本类型、String、byte[]、可序列化 Java 对象     |
| 内存 Backend   | 仅在服务进程内存中托管数据                       |
| 文件 Backend   | AtomicFile + 二进制 KV 文件                      |
| 数据库 Backend | SQLite + WAL + KV 表                             |
| 统一通知       | Binder Observer + revision                       |
| 统一权限       | namespace 所有权及读写授权                       |

### 我的最终建议

这次不再把缓存和持久化混在一起，而是让它们成为并列的三种独立存储能力。

Apps 层唯一需要掌握的规则就是：

```
// 开机期间临时共享数据
GlobalStore memory = manager.memory("device");

// 需要跨重启保存的轻量配置
GlobalStore file = manager.file("device");

// 需要跨重启保存的大量或频繁修改的数据
GlobalStore database = manager.database("device");
```

三者拥有同一套基本类型和 Java 对象读写、修改、监听接口。GlobalDataService 不认识任何业务类，只认识数据身份、类型元数据、字节值、版本和权限。

这套架构满足你的核心基线：任何 App 将来新增全局基本类型或可序列化的引用类型对象，都只需要修改 Apps 层代码。三种托管存储、客户端 SDK、AIDL、GlobalDataService 和 AOSP Framework 不需要随业务数据模型的增加而修改。

接下来进行源码落地时，应该优先把 `GlobalStore` 通用接口、`StorageBackend` 统一接口、数据序列化协议、namespace 权限和 Observer 协议固定下来，再分别开发三个 Backend。这样能够从架构上保证后续业务扩展不侵入系统服务。