package com.oem.globaldata.client;

/** 成功写入或删除后的版本信息，可供界面展示或后续业务决策。 */
public final class WriteResult {
    /** 本次成功操作后的 namespace 单调递增版本。 */
    public final long revision;
    /** 执行本次操作的服务实例标识。 */
    public final String serviceEpoch;
    /**
     * 封装服务端返回的提交结果。
     * @param revision 提交后的版本
     * @param serviceEpoch 服务实例标识
     */
    public WriteResult(long revision, String serviceEpoch) {
        this.revision = revision; this.serviceEpoch = serviceEpoch;
    }
}
