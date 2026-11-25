package com.oem.globaldata.contracts;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertSame;

import com.oem.globaldata.client.Codecs;
import com.oem.globaldata.client.DataKey;
import com.oem.globaldata.ipc.Wire;
import org.junit.Test;

public final class DataAudienceKeysTest {
    @Test public void fixedFactoriesUseExpectedCompleteAddresses() {
        assertAddress(DeviceDataKeys.fileStringDeviceAudienceDemo(), Wire.FILE, Wire.DEVICE_DE,
                "demo_audience_device_all");
        assertAddress(DeviceDataKeys.memoryStringUserAllAudienceDemo(), Wire.MEMORY, Wire.USER_CE,
                "demo_audience_user_all");
        assertAddress(DeviceDataKeys.databaseStringUserPreinstalledAudienceDemo(), Wire.DATABASE,
                Wire.USER_CE, "demo_audience_user_preinstalled");
        assertAddress(DeviceDataKeys.fileStringUserSameSignatureAudienceDemo(), Wire.FILE,
                Wire.USER_CE, "demo_audience_user_same_signature");
    }

    private static void assertAddress(DataKey<String> key, int store, int scope,
            String name) {
        assertEquals(store, key.store);
        assertEquals(scope, key.scope);
        assertEquals("com.oem.globaldata.app1", key.ownerPackage);
        assertEquals(name, key.key);
        assertSame(Codecs.STRING, key.codec);
    }
}
