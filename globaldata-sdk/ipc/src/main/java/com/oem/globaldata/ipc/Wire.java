package com.oem.globaldata.ipc;

/**
 * 客户端与系统服务共用的 v2 协议常量。数值和 Bundle 字段名已经成为线缆格式，
 * 修改它们会让旧 SDK 和旧系统镜像对同一请求产生不同解释。
 */
public final class Wire {
    /** 常量容器不创建实例。 */
    private Wire() {}

    /** 主版本不同则拒绝连接，防止误解读请求结构。 */
    public static final int PROTOCOL_MAJOR = 2;
    /** 服务端次版本至少达到此值，客户端才认为所需能力齐备。 */
    public static final int PROTOCOL_MINOR = 0;
    /** 单个值的 payload 上限为 64 KiB，客户端和服务端都会检查。 */
    public static final int MAX_PAYLOAD = 64 * 1024;

    /** system_server 内存暂存，服务进程重启后值消失。 */
    public static final int MEMORY = 1;
    /** 每个 owner 地址 的 AtomicFile 快照。 */
    public static final int FILE = 2;
    /** SQLite KV 存储，值与版本在同一事务内更新。 */
    public static final int DATABASE = 3;

    /** 设备级 DE 地址，平台 owner 可为指定 Key 授权。 */
    public static final int DEVICE_DE = 1;
    /** 调用方所属 Android 用户的 DE 地址，可供解锁前的受控组件使用。 */
    public static final int USER_DE = 2;
    /** 调用方所属 Android 用户的 CE 地址，首次解锁前不可用。 */
    public static final int USER_CE = 3;

    /** 无条件创建或覆盖。 */
    public static final int UPSERT = 1;
    /** 仅 Key 不存在时创建，否则返回 CONFLICT。 */
    public static final int IF_ABSENT = 2;
    /** 比较快照 revision 与服务 epoch，拒绝旧快照写入。 */
    public static final int IF_REVISION = 3;

    /** 请求成功。 */
    public static final int OK = 0;
    /** 目标 Key 或授权目标包不存在。 */
    public static final int NOT_FOUND = 1;
    /** UID、owner、签名、scope 或 ACL 不满足访问条件。 */
    public static final int PERMISSION_DENIED = 2;
    /** 仅不存在写入或 CAS 的前置条件不成立。 */
    public static final int CONFLICT = 3;
    /** 单值、后端容量或事件速率超出配额。 */
    public static final int QUOTA_EXCEEDED = 4;
    /** 协议能力或业务 schema 与客户端不兼容。 */
    public static final int UNSUPPORTED = 5;
    /** USER_CE 对应用户尚未完成本次开机的首次解锁。 */
    public static final int LOCKED = 6;
    /** Bridge、Binder 或存储后端不可用。 */
    public static final int STORAGE_UNAVAILABLE = 7;
    /** 地址、操作模式或编码数据无效。 */
    public static final int BAD_REQUEST = 8;
    /** 服务端有界任务队列已满。 */
    public static final int BUSY = 9;

    /** 读取指定 Key 上的值。 */
    public static final int READ = 1;
    /** 在指定 Key 上创建或更新值。 */
    public static final int WRITE = 2;
    /** 删除指定 Key 上的值。 */
    public static final int DELETE = 4;
    /** 观察单个 Key 的变化；监听后读当前值还需 READ。 */
    public static final int OBSERVE = 8;
    /** 发布指定 topic 的无载荷事件。 */
    public static final int PUBLISH = 16;
    /** 订阅指定 topic 的无载荷事件。 */
    public static final int SUBSCRIBE = 32;

    /** 写入前 Key 不存在，本次创建了值。 */
    public static final int CREATE_OP = 1;
    /** 写入前 Key 已存在，本次替换了值。 */
    public static final int UPDATE_OP = 2;
    /** 本次删除了已存在的值。 */
    public static final int DELETE_OP = 3;


    /** 服务端支持内存后端。 */
    public static final long FEATURE_MEMORY = 1L;
    /** 服务端支持文件后端。 */
    public static final long FEATURE_FILE = 1L << 1;
    /** 服务端支持 SQLite 后端。 */
    public static final long FEATURE_DATABASE = 1L << 2;
    /** 服务端支持 revision 与 epoch 条件写入。 */
    public static final long FEATURE_CAS = 1L << 3;
    /** 服务端支持数据变化订阅。 */
    public static final long FEATURE_OBSERVE = 1L << 4;
    /** 服务端支持无载荷 EventBus。 */
    public static final long FEATURE_EVENT = 1L << 5;
    /** 服务端支持 owner 管理的动态授权。 */
    public static final long FEATURE_ACL = 1L << 6;
    /** 服务端区分 DE/CE 与用户级/设备级地址。 */
    public static final long FEATURE_DE_CE = 1L << 7;
    /** 单 Key 规则的生效对象。设备级与用户级对象不可混用。 */
    public static final int AUDIENCE_DEVICE_ALL = 1, AUDIENCE_USER_ALL = 2,
            AUDIENCE_USER_PREINSTALLED = 3, AUDIENCE_USER_SAME_SIGNATURE = 4;
    /** 支持按完整数据地址授予范围权限。 */
    public static final long FEATURE_DATA_AUDIENCE = 1L << 8;
    /** 当前客户端连接时要求的能力集合。 */
    public static final long ALL_FEATURES = FEATURE_MEMORY | FEATURE_FILE | FEATURE_DATABASE
            | FEATURE_CAS | FEATURE_OBSERVE | FEATURE_EVENT | FEATURE_ACL | FEATURE_DE_CE
            | FEATURE_DATA_AUDIENCE;

    /** 响应状态码，int。 */
    public static final String STATUS = "status";
    /** 数据地址中的后端类型，int。 */
    public static final String STORE = "store";
    /** 数据或事件的用户/设备作用域，int。 */
    public static final String SCOPE = "scope";
    /** 服务端核对的资源 owner 包名，String。 */
    public static final String OWNER = "owner";
    /** 单条数据名，String。 */
    public static final String KEY = "key";
    /** 无载荷事件的完整主题名，String。 */
    public static final String TOPIC = "topic";
    /** Codec 的稳定业务类型标识，String。 */
    public static final String SCHEMA_ID = "schemaId";
    /** 值实际写入时使用的 schema 版本，int。 */
    public static final String SCHEMA_VERSION = "schemaVersion";
    /** 字节格式说明，服务端保存但不解析，String。 */
    public static final String ENCODING = "encoding";
    /** Codec 生成的载荷，byte[]。 */
    public static final String PAYLOAD = "payload";
    /** read 响应中的已编码值 Bundle。 */
    public static final String VALUE = "value";
    /** read 响应是否含值，boolean。 */
    public static final String PRESENT = "present";
    /** 值版本，或不存在时 同一 owner 地址的版本高水位，long。 */
    public static final String REVISION = "revision";
    /** 本次服务进程实例标识，String。 */
    public static final String EPOCH = "epoch";
    /** CAS 请求携带的期望服务实例标识，String。 */
    public static final String EXPECTED_EPOCH = "expectedEpoch";
    /** 数据变更通知中的 CREATE/UPDATE/DELETE 操作码。 */
    public static final String OPERATION = "operation";
    /** grant/revoke 请求中的权限位掩码。 */
    public static final String RIGHTS = "rights";
    /** capability 响应的协议主版本，int。 */
    public static final String MAJOR = "major";
    /** capability 响应的协议次版本，int。 */
    public static final String MINOR = "minor";
    /** capability 响应的服务端载荷上限，int。 */
    public static final String MAX_BYTES = "maxBytes";
    /** capability 响应的能力位集合，long。 */
    public static final String FEATURES = "features";
}
