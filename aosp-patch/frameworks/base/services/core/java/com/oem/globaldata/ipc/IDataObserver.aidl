package com.oem.globaldata.ipc;
import android.os.Bundle;
oneway interface IDataObserver {
    void onDataChanged(in Bundle event);
    void onResyncRequired(String serviceEpoch);
}
