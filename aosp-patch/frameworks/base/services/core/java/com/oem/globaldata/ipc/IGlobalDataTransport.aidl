package com.oem.globaldata.ipc;

import android.os.Bundle;
import com.oem.globaldata.ipc.IDataObserver;
import com.oem.globaldata.ipc.IEventListener;

/**
 * v1 稳定传输接口。Bundle 字段由 Wire 定义，只传地址、元信息与编码字节，
 * system_server 不加载 App 的业务对象类。方法顺序参与 AIDL 事务编号，不可随意调整。
 * 选择稳定的字节协议，是为了让系统镜像与客户端 SDK 分别升级时仍可通过能力握手
 * 判断兼容性，而不依赖双方必须同时拥有相同的业务 Java 类。
 */
interface IGlobalDataTransport {
    /** SDK 先验证版本、载荷限制和能力位，避免连接后才在业务请求中发现协议不兼容。 */
    Bundle getCapabilities();
    /** 按完整地址读取；Key 不存在时返回 NOT_FOUND、present=false 和 namespace 版本高水位。 */
    Bundle read(in Bundle key);
    /** mode 决定无条件、仅不存在或 CAS 写入；CAS 同时检查 revision 和 key 中的 expectedEpoch。 */
    Bundle write(in Bundle key, in Bundle value, int mode, long expectedRevision);
    /** expectedRevision=-1 表示无条件删除；条件删除还检查 key 中的 expectedEpoch。 */
    Bundle delete(in Bundle key, long expectedRevision);
    /** 真实 owner 为已安装目标包授予数据 namespace 或事件 topic 的指定权限位。 */
    Bundle grant(in Bundle resource, String targetPackage, int rights);
    /** 真实 owner 撤销目标包在同一资源上的指定权限位。 */
    Bundle revoke(in Bundle resource, String targetPackage, int rights);
    /** 订阅单个 Key 的变化；成功返回正 ID，失败返回 -1，注册本身不读取初始值。 */
    long subscribeData(in Bundle key, IDataObserver observer);
    /** 仅原订阅 UID 可凭 ID 取消数据监听。 */
    void unsubscribeData(long subscriptionId);
    /** 事件只承担在线唤醒信号，避免把状态副本经队列传播；成功仅表示接受并排队。 */
    Bundle publishEvent(in Bundle address);
    /** 订阅完整 topic；失败返回 -1，离线期间的事件不补发。 */
    long subscribeEvent(in Bundle address, IEventListener observer);
    /** 仅原订阅 UID 可凭 ID 取消事件监听。 */
    void unsubscribeEvent(long subscriptionId);
}
