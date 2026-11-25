package com.android.server.globaldata;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import android.os.Bundle;
import androidx.test.platform.app.InstrumentationRegistry;
import com.oem.globaldata.ipc.IDataObserver;
import com.oem.globaldata.ipc.Wire;
import java.io.IOException;
import java.util.concurrent.RejectedExecutionException;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

public final class DataObserversTest {
    @Rule public final TemporaryFolder temp = new TemporaryFolder(
            InstrumentationRegistry.getInstrumentation().getTargetContext().getCacheDir());

    private static final String OWNER = "com.example.owner";
    private static final int UID = 10001;
    private static final StoreAddress KEY = new StoreAddress(Wire.MEMORY, Wire.USER_DE, 0,
            OWNER, "key");
    private boolean identityUnavailable;
    private boolean ownsPackage = true;
    private DataObservers hub;

    @Before public void setUp() throws IOException {
        AccessController acl = new AccessController(temp.newFolder(), new AccessController.Identities() {
            @Override public boolean platformSigned(int uid) { return false; }
            @Override public String signature(String name, int user) { return "a".repeat(64); }
            @Override public String signatureInAnyUser(String name) { return signature(name, 0); }
            @Override public boolean userUnlocked(int user) { return true; }
            @Override public Integer installedAppFlags(String name, int uid, int userId) {
                return ownsPackage && OWNER.equals(name) && uid == UID ? 0 : null;
            }

            @Override public String[] packagesForUid(int uid) {
                if (identityUnavailable) throw new IllegalStateException("identity unavailable");
                return ownsPackage ? new String[]{OWNER} : new String[0];
            }
        });
        hub = new DataObservers(acl, task -> {
            throw new RejectedExecutionException("queue full");
        });
    }

    private static final class Observer extends IDataObserver.Stub {
        int resyncs;
        String epoch;
        boolean fail;

        @Override public void onDataChanged(Bundle event) {
            throw new AssertionError("overflow should only request resync");
        }

        @Override public void onResyncRequired(String epoch) {
            resyncs++;
            this.epoch = epoch;
            if (fail) throw new IllegalStateException("callback failure");
        }
    }

    @Test public void overflowPreservesSubscriptionAfterTransientIdentityFailure() {
        Observer observer = new Observer();
        assertTrue(hub.add(UID, KEY, observer) > 0);
        identityUnavailable = true;
        hub.changed(KEY, Wire.UPDATE_OP, 1, "epoch");
        assertEquals(0, observer.resyncs);

        identityUnavailable = false;
        hub.changed(KEY, Wire.UPDATE_OP, 2, "epoch");
        assertEquals(1, observer.resyncs);
        assertEquals("epoch", observer.epoch);

        ownsPackage = false;
        hub.changed(KEY, Wire.UPDATE_OP, 3, "epoch");
        ownsPackage = true;
        hub.changed(KEY, Wire.UPDATE_OP, 4, "epoch");
        assertEquals(1, observer.resyncs);
    }

    @Test public void failingOverflowCallbackDoesNotInterruptRemainingDelivery() {
        Observer broken = new Observer(), healthy = new Observer();
        broken.fail = true;
        assertTrue(hub.add(UID, KEY, broken) > 0);
        assertTrue(hub.add(UID, KEY, healthy) > 0);
        hub.changed(KEY, Wire.UPDATE_OP, 1, "epoch");
        assertEquals(1, broken.resyncs);
        assertEquals(1, healthy.resyncs);
        hub.changed(KEY, Wire.UPDATE_OP, 2, "epoch");
        assertEquals(1, broken.resyncs);
        assertEquals(2, healthy.resyncs);
    }
}
