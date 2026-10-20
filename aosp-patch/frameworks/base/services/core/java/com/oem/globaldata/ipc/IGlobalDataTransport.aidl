package com.oem.globaldata.ipc;

import android.os.Bundle;
import com.oem.globaldata.ipc.IDataObserver;
import com.oem.globaldata.ipc.IEventListener;

/** Protocol v1. Bundle fields are defined in Wire.java; never carry app Java objects. */
interface IGlobalDataTransport {
    Bundle getCapabilities();
    Bundle read(in Bundle key);
    Bundle write(in Bundle key, in Bundle value, int mode, long expectedRevision);
    Bundle delete(in Bundle key, long expectedRevision);
    Bundle grant(in Bundle resource, String targetPackage, int rights);
    Bundle revoke(in Bundle resource, String targetPackage, int rights);
    long subscribeData(in Bundle key, IDataObserver observer);
    void unsubscribeData(long subscriptionId);
    Bundle publishEvent(in Bundle address);
    long subscribeEvent(in Bundle address, IEventListener observer);
    void unsubscribeEvent(long subscriptionId);
}
