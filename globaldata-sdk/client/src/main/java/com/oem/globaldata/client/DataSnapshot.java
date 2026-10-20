package com.oem.globaldata.client;

/**
 * 一次读取的不可变结果。不存在时 {@link #present} 为 false、{@link #value} 为 null，
 * 仍携带 namespace 的版本高水位，供不存在状态的 CAS 使用。
 */
public final class DataSnapshot<T> {
    /** 是否确实存有值；不能用 value 为 null 推断整数 0 等有效值是否存在。 */
    public final boolean present;
    /** 用当前 Key 的 Codec 解码后的业务值；不存在时为 null。 */
    public final T value;
    /** 有值时是值版本，不存在时是 namespace 版本高水位。 */
    public final long revision;
    /** 本次 system_server 服务实例标识；CAS 同时比较它，拒绝重启前的旧快照。 */
    public final String serviceEpoch;
    /**
     * 保存一次完整读取结果；业务代码通常通过 GlobalDataManager.read 获取。
     * @param present 记录是否存在
     * @param value 解码后的值或 null
     * @param revision 当前值版本或 namespace 高水位
     * @param serviceEpoch 服务实例标识
     */
    public DataSnapshot(boolean present, T value, long revision, String serviceEpoch) {
        this.present = present; this.value = value; this.revision = revision;
        this.serviceEpoch = serviceEpoch;
    }
}
