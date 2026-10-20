package com.oem.globaldata.client;

/** GlobalData 操作失败，保留 Wire 状态码供调用方按错误类型处理。 */
public final class GlobalDataException extends RuntimeException {
    /** Wire.OK 之外的状态码，例如 LOCKED、CONFLICT 或 PERMISSION_DENIED。 */
    public final int status;
    /**
     * @param status 协议状态码
     * @param message 面向诊断的错误描述
     */
    public GlobalDataException(int status, String message) { super(message); this.status = status; }
    /**
     * 在 Binder 失败或 Codec 失败时保留底层异常链。
     * @param status 协议状态码
     * @param message 面向诊断的错误描述
     * @param cause 原始异常
     */
    public GlobalDataException(int status, String message, Throwable cause) {
        super(message, cause); this.status = status;
    }
}
