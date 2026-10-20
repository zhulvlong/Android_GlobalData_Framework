package com.oem.globaldata.ipc;
import android.os.Bundle;
oneway interface IEventListener {
    void onEvent(in Bundle event);
}
