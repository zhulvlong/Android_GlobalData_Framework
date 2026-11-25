package com.android.server.globaldata;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.content.pm.ApplicationInfo;
import android.os.Bundle;
import android.os.UserHandle;
import androidx.test.platform.app.InstrumentationRegistry;
import com.oem.globaldata.ipc.IDataObserver;
import com.oem.globaldata.ipc.Wire;
import java.io.File;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

public final class AccessControllerAudienceTest {
    @Rule public final TemporaryFolder temp = new TemporaryFolder(
            InstrumentationRegistry.getInstrumentation().getTargetContext().getCacheDir());

    private static final String OWNER = "com.example.owner";
    private static final String OWNER_SIGNATURE = "a".repeat(64), OTHER_SIGNATURE = "b".repeat(64);
    private static final int OWNER_UID = 10001, CLIENT_UID = 10002, ROM_UID = 10003;
    private static final int MATCH_UID = 10004, LATE_UID = 10005;
    private static final int SECONDARY_CLIENT = UserHandle.getUid(10, CLIENT_UID);
    private static final int SECONDARY_OWNER = UserHandle.getUid(10, OWNER_UID);
    private static final int ALL_RIGHTS = Wire.READ | Wire.WRITE | Wire.DELETE | Wire.OBSERVE;
    private static final StoreAddress USER_KEY = key(Wire.FILE, Wire.USER_DE, 0, "config");
    private static final StoreAddress DEVICE_KEY = key(Wire.FILE, Wire.DEVICE_DE, 0, "config");

    private final FakeIdentities ids = new FakeIdentities();
    private File directory;
    private File file;
    private AccessController acl;

    private record App(String name, String signature, int flags) {}

    private static final class FakeIdentities implements AccessController.Identities {
        final Map<Integer, App> apps = new HashMap<>();
        boolean ownerPlatform;
        boolean unlocked = true;

        @Override public boolean platformSigned(int uid) {
            return ownerPlatform && uid == OWNER_UID;
        }

        @Override public String signature(String name, int user) {
            for (var entry : apps.entrySet()) {
                if (UserHandle.getUserId(entry.getKey()) == user && entry.getValue().name().equals(name)) {
                    return entry.getValue().signature();
                }
            }
            return null;
        }

        @Override public String signatureInAnyUser(String name) {
            for (App app : apps.values()) if (app.name().equals(name)) return app.signature();
            return null;
        }

        @Override public String[] packagesForUid(int uid) {
            App app = apps.get(uid);
            return app == null ? null : new String[]{app.name()};
        }

        @Override public Integer installedAppFlags(String name, int uid, int user) {
            App app = apps.get(uid);
            return app != null && UserHandle.getUserId(uid) == user && app.name().equals(name)
                    ? app.flags() : null;
        }

        @Override public boolean userUnlocked(int user) { return unlocked; }
    }

    @Before public void setUp() throws IOException {
        directory = temp.newFolder();
        File v2 = Files.createDirectory(directory.toPath().resolve("v2")).toFile();
        file = new File(v2, "acl.bin");
        ids.apps.put(OWNER_UID, new App(OWNER, OWNER_SIGNATURE, ApplicationInfo.FLAG_SYSTEM));
        ids.apps.put(CLIENT_UID, new App("com.example.client", OTHER_SIGNATURE, 0));
        ids.apps.put(ROM_UID, new App("com.example.rom", OTHER_SIGNATURE,
                ApplicationInfo.FLAG_UPDATED_SYSTEM_APP));
        ids.apps.put(MATCH_UID, new App("com.example.match", OWNER_SIGNATURE, 0));
        acl = open();
    }

    private AccessController open() { return new AccessController(new File(directory, "v2"), ids); }

    private static StoreAddress key(int store, int scope, int user, String name) {
        return new StoreAddress(store, scope, user, OWNER, name);
    }

    private int change(int uid, StoreAddress key, int audience, int rights, boolean grant) {
        return acl.changeDataAudience(uid, key, audience, rights, grant);
    }

    private int access(int uid, StoreAddress key, int rights) {
        return acl.authorizeData(uid, key, rights, rights == Wire.WRITE);
    }

    @Test public void deviceGrantReachesFutureAppsAcrossUsersOnOnlyTheExactKey() {
        assertEquals(Wire.PERMISSION_DENIED, change(OWNER_UID, DEVICE_KEY,
                Wire.AUDIENCE_DEVICE_ALL, ALL_RIGHTS, true));
        ids.ownerPlatform = true;
        assertEquals(Wire.OK, change(OWNER_UID, DEVICE_KEY, Wire.AUDIENCE_DEVICE_ALL, ALL_RIGHTS, true));
        assertEquals(Wire.PERMISSION_DENIED, access(SECONDARY_CLIENT, DEVICE_KEY, Wire.READ));
        ids.apps.put(SECONDARY_CLIENT, new App("com.example.client", OTHER_SIGNATURE, 0));
        for (int uid : new int[]{CLIENT_UID, SECONDARY_CLIENT}) {
            for (int bit : new int[]{Wire.READ, Wire.WRITE, Wire.OBSERVE, Wire.DELETE}) {
                assertEquals(Wire.OK, access(uid, DEVICE_KEY, bit));
            }
        }

        for (StoreAddress other : new StoreAddress[]{
                key(Wire.MEMORY, Wire.DEVICE_DE, 0, "config"),
                key(Wire.DATABASE, Wire.DEVICE_DE, 0, "config"),
                key(Wire.FILE, Wire.DEVICE_DE, 0, "neighbor")}) {
            assertEquals(Wire.PERMISSION_DENIED, access(SECONDARY_CLIENT, other, Wire.READ));
        }
        assertEquals(Wire.PERMISSION_DENIED, change(SECONDARY_CLIENT, DEVICE_KEY,
                Wire.AUDIENCE_DEVICE_ALL, Wire.READ, false));

        acl = open();
        assertEquals(Wire.OK, access(SECONDARY_CLIENT, DEVICE_KEY, ALL_RIGHTS));
        assertEquals(Wire.OK, change(OWNER_UID, DEVICE_KEY, Wire.AUDIENCE_DEVICE_ALL, Wire.WRITE, false));
        assertEquals(Wire.PERMISSION_DENIED, access(SECONDARY_CLIENT, DEVICE_KEY, Wire.WRITE));
        assertEquals(Wire.OK, access(SECONDARY_CLIENT, DEVICE_KEY, Wire.READ | Wire.OBSERVE));
    }

    @Test public void deviceDirectGrantUsesExactKeyAndAnInstallationInAnyUser() {
        String target = "com.example.client";
        ids.apps.remove(CLIENT_UID);
        ids.apps.put(SECONDARY_CLIENT, new App(target, OTHER_SIGNATURE, 0));
        assertEquals(Wire.PERMISSION_DENIED, acl.changeDataGrant(
                OWNER_UID, DEVICE_KEY, target, Wire.READ, true));
        ids.ownerPlatform = true;
        assertEquals(Wire.NOT_FOUND, acl.changeDataGrant(
                OWNER_UID, DEVICE_KEY, "com.example.absent", Wire.READ, true));
        assertEquals(Wire.NOT_FOUND, acl.changeDataGrant(
                OWNER_UID, USER_KEY, target, Wire.READ, true));
        assertEquals(Wire.OK, acl.changeDataGrant(
                OWNER_UID, DEVICE_KEY, target, Wire.READ | Wire.OBSERVE, true));
        assertEquals(Wire.PERMISSION_DENIED, access(CLIENT_UID, DEVICE_KEY, Wire.READ));
        ids.apps.put(CLIENT_UID, new App(target, OTHER_SIGNATURE, 0));

        acl = open();
        assertEquals(Wire.OK, access(CLIENT_UID, DEVICE_KEY, Wire.READ | Wire.OBSERVE));
        assertEquals(Wire.OK, access(SECONDARY_CLIENT, DEVICE_KEY, Wire.READ | Wire.OBSERVE));
        assertEquals(Wire.PERMISSION_DENIED, access(MATCH_UID, DEVICE_KEY, Wire.READ));
        assertEquals(Wire.PERMISSION_DENIED, access(CLIENT_UID, DEVICE_KEY, Wire.WRITE));
        assertEquals(Wire.PERMISSION_DENIED, access(CLIENT_UID, USER_KEY, Wire.READ));
        assertEquals(Wire.PERMISSION_DENIED, access(CLIENT_UID,
                key(Wire.MEMORY, Wire.DEVICE_DE, 0, "config"), Wire.READ));
        assertEquals(Wire.PERMISSION_DENIED, access(CLIENT_UID,
                key(Wire.FILE, Wire.DEVICE_DE, 0, "neighbor"), Wire.READ));

        assertEquals(Wire.OK, acl.changeDataGrant(
                OWNER_UID, DEVICE_KEY, target, Wire.OBSERVE, false));
        assertEquals(Wire.PERMISSION_DENIED, access(SECONDARY_CLIENT, DEVICE_KEY, Wire.OBSERVE));
        assertEquals(Wire.OK, access(SECONDARY_CLIENT, DEVICE_KEY, Wire.READ));
        ids.apps.put(SECONDARY_CLIENT, new App(target, OWNER_SIGNATURE, 0));
        assertEquals(Wire.PERMISSION_DENIED, access(SECONDARY_CLIENT, DEVICE_KEY, Wire.READ));
        ids.apps.remove(OWNER_UID);
        assertEquals(Wire.PERMISSION_DENIED, access(CLIENT_UID, DEVICE_KEY, Wire.READ));
    }

    @Test public void userAudiencesAndDirectGrantsCombineWithoutCrossingUsers() {
        assertEquals(Wire.OK, change(OWNER_UID, USER_KEY, Wire.AUDIENCE_USER_ALL, Wire.READ, true));
        assertEquals(Wire.OK, change(OWNER_UID, USER_KEY, Wire.AUDIENCE_USER_PREINSTALLED, Wire.WRITE, true));
        assertEquals(Wire.OK, change(OWNER_UID, USER_KEY, Wire.AUDIENCE_USER_SAME_SIGNATURE, Wire.DELETE, true));
        assertEquals(Wire.OK, acl.changeDataGrant(OWNER_UID, USER_KEY,
                "com.example.match", Wire.OBSERVE, true));

        assertEquals(Wire.OK, access(MATCH_UID, USER_KEY, Wire.READ | Wire.DELETE | Wire.OBSERVE));
        assertEquals(Wire.OK, access(ROM_UID, USER_KEY, Wire.READ | Wire.WRITE));
        assertEquals(Wire.PERMISSION_DENIED, access(CLIENT_UID, USER_KEY, Wire.WRITE));
        assertEquals(Wire.PERMISSION_DENIED, access(ROM_UID, USER_KEY, Wire.DELETE));
        ids.apps.put(LATE_UID, new App("com.example.late", OTHER_SIGNATURE, 0));
        assertEquals(Wire.OK, access(LATE_UID, USER_KEY, Wire.READ));

        ids.apps.put(SECONDARY_OWNER, new App(OWNER, OWNER_SIGNATURE, 0));
        ids.apps.put(SECONDARY_CLIENT, new App("com.example.client", OTHER_SIGNATURE, 0));
        StoreAddress secondary = key(Wire.FILE, Wire.USER_DE, 10, "config");
        assertEquals(Wire.PERMISSION_DENIED, access(SECONDARY_CLIENT, USER_KEY, Wire.READ));
        assertEquals(Wire.PERMISSION_DENIED, access(SECONDARY_CLIENT, secondary, Wire.READ));
        assertEquals(Wire.OK, change(SECONDARY_OWNER, secondary, Wire.AUDIENCE_USER_ALL, Wire.READ, true));
        assertEquals(Wire.OK, access(SECONDARY_CLIENT, secondary, Wire.READ));

        assertEquals(Wire.OK, change(OWNER_UID, USER_KEY, Wire.AUDIENCE_USER_SAME_SIGNATURE,
                Wire.READ | Wire.DELETE, true));
        assertEquals(Wire.OK, change(OWNER_UID, USER_KEY, Wire.AUDIENCE_USER_ALL, Wire.READ, false));
        assertEquals(Wire.OK, access(MATCH_UID, USER_KEY, Wire.READ | Wire.OBSERVE));
        assertEquals(Wire.PERMISSION_DENIED, access(CLIENT_UID, USER_KEY, Wire.READ));
        assertEquals(Wire.OK, change(OWNER_UID, USER_KEY, Wire.AUDIENCE_USER_SAME_SIGNATURE, Wire.READ, false));
        assertEquals(Wire.PERMISSION_DENIED, access(MATCH_UID, USER_KEY, Wire.READ));
        assertEquals(Wire.OK, access(MATCH_UID, USER_KEY, Wire.DELETE | Wire.OBSERVE));
        ids.apps.put(MATCH_UID, new App("com.example.match", OTHER_SIGNATURE, 0));
        assertEquals(Wire.PERMISSION_DENIED, access(MATCH_UID, USER_KEY, Wire.DELETE | Wire.OBSERVE));
    }

    @Test public void exactUserGrantsAndAudiencesStayWithinEachStoreAndKey() {
        int[] audiences = {Wire.AUDIENCE_USER_ALL, Wire.AUDIENCE_USER_PREINSTALLED,
                Wire.AUDIENCE_USER_SAME_SIGNATURE};
        int[] consumers = {CLIENT_UID, ROM_UID, MATCH_UID};
        String[] targets = {"com.example.client", "com.example.rom", "com.example.match"};
        for (int store = Wire.MEMORY; store <= Wire.DATABASE; store++) {
            for (int i = 0; i < audiences.length; i++) {
                StoreAddress key = key(store, Wire.USER_DE, 0, "shared" + i);
                for (int otherStore = Wire.MEMORY; otherStore <= Wire.DATABASE; otherStore++) {
                    if (otherStore == store) continue;
                    StoreAddress other = key(otherStore, Wire.USER_DE, 0, key.key);
                    assertEquals(Wire.PERMISSION_DENIED, access(consumers[i], other, Wire.READ));
                }
                assertEquals(Wire.OK, acl.changeDataGrant(
                        OWNER_UID, key, targets[i], Wire.READ | Wire.OBSERVE, true));
                assertEquals(Wire.OK, change(OWNER_UID, key, audiences[i], Wire.READ, true));
                assertEquals(Wire.PERMISSION_DENIED, access(consumers[i],
                        key(store, Wire.USER_DE, 0, "neighbor"), Wire.READ));
                assertEquals(Wire.PERMISSION_DENIED, access(consumers[i],
                        key(store, Wire.USER_CE, 0, key.key), Wire.READ));
                assertEquals(Wire.OK, access(consumers[i], key, Wire.READ | Wire.OBSERVE));
                assertEquals(Wire.OK, acl.changeDataGrant(
                        OWNER_UID, key, targets[i], Wire.READ, false));
                assertEquals(Wire.OK, access(consumers[i], key, Wire.READ | Wire.OBSERVE));
                assertEquals(Wire.OK, change(OWNER_UID, key, audiences[i], Wire.READ, false));
                assertEquals(Wire.PERMISSION_DENIED, access(consumers[i], key, Wire.READ));
                assertEquals(Wire.OK, access(consumers[i], key, Wire.OBSERVE));
            }
        }
    }

    @Test public void ceAndPlatformRestrictionsStillApply() {
        StoreAddress ce = key(Wire.MEMORY, Wire.USER_CE, 0, "config");
        ids.unlocked = false;
        assertEquals(Wire.LOCKED, change(OWNER_UID, ce, Wire.AUDIENCE_USER_ALL, Wire.READ, true));
        ids.unlocked = true;
        assertEquals(Wire.OK, change(OWNER_UID, ce, Wire.AUDIENCE_USER_ALL, Wire.READ, true));
        assertEquals(Wire.OK, access(CLIENT_UID, ce, Wire.READ));
        ids.unlocked = false;
        assertEquals(Wire.LOCKED, access(CLIENT_UID, ce, Wire.READ));
        ids.unlocked = true;

        assertEquals(Wire.BAD_REQUEST, change(OWNER_UID, ce, Wire.AUDIENCE_DEVICE_ALL, Wire.READ, true));
        assertEquals(Wire.BAD_REQUEST, change(OWNER_UID, DEVICE_KEY,
                Wire.AUDIENCE_USER_ALL, Wire.READ, true));
        assertEquals(Wire.BAD_REQUEST, change(OWNER_UID, ce, 999, Wire.READ, true));
        assertEquals(Wire.BAD_REQUEST, change(OWNER_UID, ce, Wire.AUDIENCE_USER_ALL, Wire.PUBLISH, true));
        assertEquals(Wire.BAD_REQUEST, change(OWNER_UID, ce, Wire.AUDIENCE_USER_ALL, 0, true));
        assertEquals(Wire.PERMISSION_DENIED, access(OWNER_UID, DEVICE_KEY, Wire.READ));
    }

    @Test public void recordedOwnerIdentityControlsAllAudienceAccess() {
        ids.ownerPlatform = true;
        assertEquals(Wire.OK, change(OWNER_UID, DEVICE_KEY, Wire.AUDIENCE_DEVICE_ALL, Wire.READ, true));
        assertEquals(Wire.OK, change(OWNER_UID, USER_KEY, Wire.AUDIENCE_USER_ALL, Wire.READ, true));
        ids.apps.remove(OWNER_UID);
        assertEquals(Wire.PERMISSION_DENIED, access(CLIENT_UID, DEVICE_KEY, Wire.READ));
        assertEquals(Wire.PERMISSION_DENIED, access(CLIENT_UID, USER_KEY, Wire.READ));
        ids.apps.put(OWNER_UID, new App(OWNER, OTHER_SIGNATURE, ApplicationInfo.FLAG_SYSTEM));
        assertEquals(Wire.PERMISSION_DENIED, access(CLIENT_UID, DEVICE_KEY, Wire.READ));
        assertEquals(Wire.PERMISSION_DENIED, change(OWNER_UID, USER_KEY,
                Wire.AUDIENCE_USER_ALL, Wire.READ, true));
    }

    @Test public void deviceObserversRecheckRevocationAtDeliveryAndOverflow() {
        ids.ownerPlatform = true;
        ids.apps.put(SECONDARY_CLIENT, new App("com.example.client", OTHER_SIGNATURE, 0));
        for (boolean overflow : new boolean[]{false, true}) {
            assertEquals(Wire.OK, change(OWNER_UID, DEVICE_KEY,
                    Wire.AUDIENCE_DEVICE_ALL, Wire.OBSERVE, true));
            AtomicInteger delivered = new AtomicInteger();
            ArrayDeque<Runnable> pending = new ArrayDeque<>();
            DataObservers observers = new DataObservers(acl, runnable -> {
                if (overflow) throw new RejectedExecutionException("test overflow");
                pending.add(runnable);
            });
            long id = observers.add(SECONDARY_CLIENT, DEVICE_KEY, new IDataObserver.Stub() {
                @Override public void onDataChanged(Bundle event) { delivered.incrementAndGet(); }
                @Override public void onResyncRequired(String epoch) { delivered.incrementAndGet(); }
            });
            assertTrue(id > 0);
            observers.changed(DEVICE_KEY, Wire.CREATE_OP, 1, "epoch");
            if (!overflow) pending.remove().run();
            assertEquals(1, delivered.get());

            if (!overflow) observers.changed(DEVICE_KEY, Wire.UPDATE_OP, 2, "epoch");
            assertEquals(Wire.OK, change(OWNER_UID, DEVICE_KEY,
                    Wire.AUDIENCE_DEVICE_ALL, Wire.OBSERVE, false));
            if (overflow) observers.changed(DEVICE_KEY, Wire.UPDATE_OP, 2, "epoch");
            else pending.remove().run();
            assertEquals(1, delivered.get());
        }
    }

    @Test public void freshV2IgnoresLegacyAclAndChecksChecksum() throws Exception {
        File legacy = new File(directory, "acl-v1.bin");
        byte[] oldBytes = {1, 2, 3};
        Files.write(legacy.toPath(), oldBytes);
        acl = open();
        assertEquals(Wire.OK, access(OWNER_UID, USER_KEY, Wire.READ));
        assertEquals(Wire.PERMISSION_DENIED, access(CLIENT_UID, USER_KEY, Wire.OBSERVE));
        assertEquals(Wire.OK, acl.changeDataGrant(
                OWNER_UID, USER_KEY, "com.example.client", Wire.OBSERVE, true));
        assertEquals(Wire.OK, change(OWNER_UID, USER_KEY, Wire.AUDIENCE_USER_ALL, Wire.READ, true));

        acl = open();
        assertEquals(Wire.OK, access(CLIENT_UID, USER_KEY, Wire.READ | Wire.OBSERVE));
        assertArrayEquals(oldBytes, Files.readAllBytes(legacy.toPath()));
        byte[] contents = Files.readAllBytes(file.toPath());
        // 修改成另一组合法的权限位，只能靠完整性校验阻止权限扩大。
        ByteBuffer.wrap(contents).putInt(contents.length - Long.BYTES - Integer.BYTES,
                Wire.READ | Wire.DELETE);
        Files.write(file.toPath(), contents);
        acl = open();
        assertEquals(Wire.STORAGE_UNAVAILABLE, access(OWNER_UID, USER_KEY, Wire.READ));
        assertEquals(Wire.STORAGE_UNAVAILABLE, access(CLIENT_UID, USER_KEY, Wire.READ));
        assertEquals(Wire.STORAGE_UNAVAILABLE, change(OWNER_UID, USER_KEY,
                Wire.AUDIENCE_USER_ALL, Wire.READ, false));
        assertArrayEquals(contents, Files.readAllBytes(file.toPath()));
    }

    @Test public void directGrantAndFirstOwnerBindingRollBackTogether() throws Exception {
        String target = "com.example.client";
        File pending = new File(file.getPath() + ".new");
        assertTrue(pending.mkdir());
        assertEquals(Wire.STORAGE_UNAVAILABLE, acl.changeDataGrant(
                OWNER_UID, USER_KEY, target, Wire.READ, true));
        assertFalse(file.exists());
        assertEquals(Wire.PERMISSION_DENIED, access(CLIENT_UID, USER_KEY, Wire.READ));
        Files.delete(pending.toPath());

        ids.apps.put(OWNER_UID, new App(OWNER, OTHER_SIGNATURE, ApplicationInfo.FLAG_SYSTEM));
        assertEquals(Wire.OK, acl.changeDataGrant(OWNER_UID, USER_KEY, target, Wire.READ, true));
        acl = open();
        assertEquals(Wire.OK, access(CLIENT_UID, USER_KEY, Wire.READ));
        ids.apps.put(OWNER_UID, new App(OWNER, OWNER_SIGNATURE, ApplicationInfo.FLAG_SYSTEM));
        assertEquals(Wire.PERMISSION_DENIED, access(OWNER_UID,
                key(Wire.MEMORY, Wire.USER_DE, 0, "otherStore"), Wire.WRITE));
        assertEquals(Wire.PERMISSION_DENIED, access(CLIENT_UID, USER_KEY, Wire.READ));
    }

    @Test public void commitFailureRollsBackNewOwnerAndChangesToExistingRules() throws Exception {
        File pending = new File(file.getPath() + ".new");
        assertTrue(pending.mkdir());
        assertEquals(Wire.STORAGE_UNAVAILABLE, change(OWNER_UID, USER_KEY,
                Wire.AUDIENCE_USER_ALL, Wire.READ, true));
        assertFalse(file.exists());
        assertEquals(Wire.PERMISSION_DENIED, access(CLIENT_UID, USER_KEY, Wire.READ));
        Files.delete(pending.toPath());
        ids.apps.put(OWNER_UID, new App(OWNER, OTHER_SIGNATURE, ApplicationInfo.FLAG_SYSTEM));
        assertEquals(Wire.OK, change(OWNER_UID, USER_KEY, Wire.AUDIENCE_USER_ALL, Wire.READ, true));

        byte[] committed = Files.readAllBytes(file.toPath());
        assertTrue(pending.mkdir());
        assertEquals(Wire.STORAGE_UNAVAILABLE, change(OWNER_UID, USER_KEY,
                Wire.AUDIENCE_USER_ALL, Wire.READ, false));
        assertEquals(Wire.OK, access(CLIENT_UID, USER_KEY, Wire.READ));
        assertArrayEquals(committed, Files.readAllBytes(file.toPath()));
        Files.delete(pending.toPath());
        acl = open();
        assertEquals(Wire.OK, access(CLIENT_UID, USER_KEY, Wire.READ));
    }
}
