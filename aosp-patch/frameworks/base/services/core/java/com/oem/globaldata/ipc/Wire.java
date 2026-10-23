package com.oem.globaldata.ipc;

/**
 * SDK 与 system_server 共用的 v1 线缆协议。这里的数值和 Bundle 字段名决定双方如何解释请求，
 * 移植时须与 SDK 副本保持一致；修改协议需同时考虑已发布客户端的兼容性。
 * 集中定义常量是为了避免客户端与系统镜像独立发布后，对同一个 Bundle 字段或状态码
 * 产生不同解释；握手中的主次版本和能力位用于及早发现这种不兼容。
 */
public final class Wire {
    /** 常量容器无需实例。 */
    private Wire() {}
    /** 主版本不一致时客户端拒绝连接。 */
    public static final int PROTOCOL_MAJOR = 1;
    /** 次版本供 SDK 判断所需能力是否齐备。 */
    public static final int PROTOCOL_MINOR = 1;
    /** 限制单次 Binder 传输与服务端内存占用；服务端在 StoredValue.parse 中再次校验。 */
    public static final int MAX_PAYLOAD = 64 * 1024;
    /** MEMORY 为进程内暂存；FILE 为每 namespace 的 AtomicFile；DATABASE 为 SQLite KV。 */
    public static final int MEMORY = 1, FILE = 2, DATABASE = 3;
    /** DEVICE_DE 为设备级 DE；USER_DE/USER_CE 分别为调用方用户的 DE/CE。CE 解锁前返回 LOCKED。 */
    public static final int DEVICE_DE = 1, USER_DE = 2, USER_CE = 3;
    /** UPSERT 无条件覆盖；IF_ABSENT 仅在 Key 不存在时创建；IF_REVISION 按 revision/epoch 做 CAS。 */
    public static final int UPSERT = 1, IF_ABSENT = 2, IF_REVISION = 3;
    /**
     * 统一响应状态：OK 成功；NOT_FOUND 不存在；PERMISSION_DENIED 拒绝访问；
     * CONFLICT 前置条件不成立；QUOTA_EXCEEDED 超出配额；UNSUPPORTED 不兼容；
     * LOCKED 用户 CE 未解锁；STORAGE_UNAVAILABLE 存储不可用；
     * BAD_REQUEST 请求无效；BUSY 服务队列已满。读操作的 NOT_FOUND 表示 Key 不存在。
     */
    public static final int OK = 0, NOT_FOUND = 1, PERMISSION_DENIED = 2,
            CONFLICT = 3, QUOTA_EXCEEDED = 4, UNSUPPORTED = 5, LOCKED = 6,
            STORAGE_UNAVAILABLE = 7, BAD_REQUEST = 8, BUSY = 9;
    /**
     * ACL 权限位：READ/WRITE/DELETE/OBSERVE 作用于数据 namespace，
     * PUBLISH/SUBSCRIBE 作用于完整事件 topic；可用按位或组合。
     */
    public static final int READ = 1, WRITE = 2, DELETE = 4, OBSERVE = 8,
            PUBLISH = 16, SUBSCRIBE = 32;
    /** 成功变更后的通知操作类型；失败不发送变更通知。 */
    public static final int CREATE_OP = 1, UPDATE_OP = 2, DELETE_OP = 3;
    /** ACL 的资源粒度：数据 namespace 或完整事件 topic。 */
    public static final int RESOURCE_DATA = 1, RESOURCE_EVENT = 2;
    /**
     * 服务端能力位：三种后端、CAS、数据观察、事件、动态 ACL、DE/CE。
     * ALL_FEATURES 是当前实现的能力并集，供 getCapabilities 返回。
     */
    public static final long FEATURE_MEMORY = 1L, FEATURE_FILE = 1L << 1,
            FEATURE_DATABASE = 1L << 2, FEATURE_CAS = 1L << 3,
            FEATURE_OBSERVE = 1L << 4, FEATURE_EVENT = 1L << 5,
            FEATURE_ACL = 1L << 6, FEATURE_DE_CE = 1L << 7;
    public static final long ALL_FEATURES = FEATURE_MEMORY | FEATURE_FILE | FEATURE_DATABASE
            | FEATURE_CAS | FEATURE_OBSERVE | FEATURE_EVENT | FEATURE_ACL | FEATURE_DE_CE;

    /**
     * Bundle 字段名：STATUS 为结果码；STORE/SCOPE/OWNER/NAMESPACE/KEY 定位数据；
     * TOPIC 定位事件；RESOURCE/RIGHTS 表示授权对象与权限位。SCHEMA_ID、SCHEMA_VERSION、
     * ENCODING、PAYLOAD 描述编码值，服务端只保存字节。VALUE/PRESENT 用于读响应；
     * REVISION 是值版本或 namespace 高水位，EPOCH/EXPECTED_EPOCH 防止跨服务实例 CAS；
     * OPERATION 用于变更通知，MAJOR/MINOR/MAX_BYTES/FEATURES 用于能力握手。
     */
    public static final String STATUS = "status", STORE = "store", SCOPE = "scope",
            OWNER = "owner", NAMESPACE = "namespace", KEY = "key", TOPIC = "topic",
            RESOURCE = "resource", SCHEMA_ID = "schemaId", SCHEMA_VERSION = "schemaVersion",
            ENCODING = "encoding", PAYLOAD = "payload", VALUE = "value", PRESENT = "present",
            REVISION = "revision", EPOCH = "epoch", EXPECTED_EPOCH = "expectedEpoch",
            OPERATION = "operation", RIGHTS = "rights",
            MAJOR = "major", MINOR = "minor", MAX_BYTES = "maxBytes", FEATURES = "features";
}
