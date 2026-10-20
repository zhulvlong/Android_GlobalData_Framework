package com.oem.globaldata.ipc;

import android.os.Bundle;
import com.oem.globaldata.ipc.IDataObserver;
import com.oem.globaldata.ipc.IEventListener;

/**
 * v1 稳定传输接口。Bundle 字段由 Wire.java 规定，只传地址、元信息和字节；
 * system_server 无需加载 App 的业务对象类。以下方法顺序参与 AIDL 事务编号，不可随意调整。
 */
interface IGlobalDataTransport {
    /** 返回协议版本、最大载荷和功能位，供 SDK 首次连接时验证。 */
    Bundle getCapabilities();
    /** 按完整数据地址读取；不存在时返回 NOT_FOUND、present=false 和版本高水位。 */
    Bundle read(in Bundle key);
    /** 按 mode 执行 upsert/仅不存在/CAS；条件写入还需 expectedRevision 和 key 中的 expectedEpoch。 */
    Bundle write(in Bundle key, in Bundle value, int mode, long expectedRevision);
    /** 删除指定 Key；expectedRevision=-1 表示无条件，否则还需 key 中的 expectedEpoch。 */
    Bundle delete(in Bundle key, long expectedRevision);
    /** 由真实 owner 为已安装目标包授予数据 namespace 或事件 topic 的权限位。 */
    Bundle grant(in Bundle resource, String targetPackage, int rights);
    /** 由真实 owner 撤销目标包在同一资源上的指定权限位。 */
    Bundle revoke(in Bundle resource, String targetPackage, int rights);
    /** 仅注册单个 Key 的变化通知，返回正订阅 ID；失败返回 -1，不自动读取初始值。 */
    long subscribeData(in Bundle key, IDataObserver observer);
    /** 仅原订阅 UID 可用其 ID 取消数据监听。 */
    void unsubscribeData(long subscriptionId);
    /** 将无载荷 topic 放入在线事件队列；成功不表示每个订阅者都已收到。 */
    Bundle publishEvent(in Bundle address);
    /** 订阅完整 topic，返回正订阅 ID；失败返回 -1，不补发离线期间的事件。 */
    long subscribeEvent(in Bundle address, IEventListener observer);
    /** 仅原订阅 UID 可用其 ID 取消事件监听。 */
    void unsubscribeEvent(long subscriptionId);
}
