package com.android.server.globaldata;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;

import androidx.test.platform.app.InstrumentationRegistry;
import com.oem.globaldata.ipc.Wire;
import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

public final class AccessControllerTest {
    @Rule public final TemporaryFolder temp = new TemporaryFolder(
            InstrumentationRegistry.getInstrumentation().getTargetContext().getCacheDir());

    private static final String OWNER = "com.example.owner";
    private static final String TARGET = "com.example.target";
    private static final int OWNER_UID = 10001, TARGET_UID = 10002;
    private static final String ORIGINAL_SIGNATURE = "a".repeat(64);
    private static final AccessController.Resource RESOURCE = new AccessController.Resource(
            Wire.RESOURCE_DATA, Wire.USER_DE, 0, OWNER, "share." + OWNER);
    private static final AccessController.Resource DEVICE_EVENT = new AccessController.Resource(
            Wire.RESOURCE_EVENT, Wire.DEVICE_DE, 0, OWNER, "sys.event");

    private final TestIdentities ids = new TestIdentities();
    private File directory;
    private File file;

    private static final class TestIdentities implements AccessController.Identities {
        String ownerSignature = ORIGINAL_SIGNATURE;

        @Override public boolean platformSigned(int uid) {
            return uid == OWNER_UID;
        }

        @Override public Integer installedAppFlags(String name, int uid, int userId) {
            return null;
        }

        @Override public String[] packagesForUid(int uid) {
            if (uid == OWNER_UID) return new String[]{OWNER};
            if (uid == TARGET_UID) return new String[]{TARGET};
            return new String[0];
        }

        @Override public String signature(String packageName, int userId) {
            if (OWNER.equals(packageName)) return ownerSignature;
            return TARGET.equals(packageName) ? "b".repeat(64) : null;
        }

        @Override public boolean userUnlocked(int userId) {
            return true;
        }
    }

    @Before public void setUp() throws IOException {
        directory = temp.newFolder();
        file = new File(directory, "acl-v1.bin");
    }

    private AccessController open() {
        return new AccessController(directory, ids);
    }

    @Test public void corruptionBlocksOwnerGrantAndPlatformUntilTrustedRestore() throws Exception {
        AccessController original = open();
        assertEquals(Wire.OK, original.changeGrant(OWNER_UID, RESOURCE, TARGET, Wire.READ, true));
        assertEquals(Wire.OK, original.authorize(OWNER_UID, DEVICE_EVENT, Wire.PUBLISH, true));
        byte[] backup = Files.readAllBytes(file.toPath());
        byte[] corrupt = java.util.Arrays.copyOf(backup, backup.length - 1);
        Files.write(file.toPath(), corrupt);

        AccessController broken = open();
        assertEquals(Wire.STORAGE_UNAVAILABLE, broken.check(OWNER_UID, RESOURCE, Wire.READ));
        assertEquals(Wire.STORAGE_UNAVAILABLE, broken.check(TARGET_UID, RESOURCE, Wire.READ));
        ids.ownerSignature = "c".repeat(64);
        assertEquals(Wire.STORAGE_UNAVAILABLE, broken.check(OWNER_UID, RESOURCE, Wire.OBSERVE));
        assertEquals(Wire.STORAGE_UNAVAILABLE,
                broken.authorize(OWNER_UID, RESOURCE, Wire.WRITE, true));
        assertEquals(Wire.STORAGE_UNAVAILABLE, broken.check(OWNER_UID, RESOURCE, Wire.DELETE));
        assertEquals(Wire.STORAGE_UNAVAILABLE,
                broken.authorize(OWNER_UID, DEVICE_EVENT, Wire.PUBLISH, true));
        assertEquals(Wire.STORAGE_UNAVAILABLE, broken.check(OWNER_UID, DEVICE_EVENT, Wire.SUBSCRIBE));
        assertEquals(Wire.STORAGE_UNAVAILABLE,
                broken.changeGrant(OWNER_UID, RESOURCE, TARGET, Wire.READ, true));
        assertEquals(Wire.STORAGE_UNAVAILABLE,
                broken.changeGrant(OWNER_UID, RESOURCE, TARGET, Wire.READ, false));
        assertArrayEquals(corrupt, Files.readAllBytes(file.toPath()));

        Files.write(file.toPath(), backup);
        AccessController restored = open();
        assertEquals(Wire.PERMISSION_DENIED, restored.check(OWNER_UID, RESOURCE, Wire.READ));
        ids.ownerSignature = ORIGINAL_SIGNATURE;
        assertEquals(Wire.OK, restored.check(OWNER_UID, RESOURCE, Wire.READ));
        assertEquals(Wire.OK, restored.check(TARGET_UID, RESOURCE, Wire.READ));
        assertEquals(Wire.OK, restored.check(OWNER_UID, DEVICE_EVENT, Wire.SUBSCRIBE));
    }

    @Test public void malformedEntriesAndTrailingBytesFailClosed() throws Exception {
        for (byte[] bytes : new byte[][]{
                entries(2, 0, Wire.READ, false), entries(1, 2, Wire.READ, false),
                entries(1, 1, Wire.PUBLISH, false), entries(1, 1, Wire.READ, true),
                entries(0, 1, Wire.READ, false)}) {
            Files.write(file.toPath(), bytes);
            AccessController acl = open();
            assertEquals(Wire.STORAGE_UNAVAILABLE, acl.check(OWNER_UID, RESOURCE, Wire.READ));
            assertArrayEquals(bytes, Files.readAllBytes(file.toPath()));
        }
    }

    @Test public void malformedOwnerAddressCannotDisableSignatureBinding() throws Exception {
        byte[] bytes = entries(1, 0, Wire.READ, false);
        int separator = RESOURCE.id().lastIndexOf('|');
        // 损坏一个 namespace 字节，文件长度和记录数仍然完整。
        bytes[12 + Short.BYTES + separator + 1] = (byte) '!';
        Files.write(file.toPath(), bytes);
        assertEquals(Wire.STORAGE_UNAVAILABLE, open().check(OWNER_UID, RESOURCE, Wire.READ));
        assertArrayEquals(bytes, Files.readAllBytes(file.toPath()));
    }

    @Test public void interruptedFirstWriteIsNotTreatedAsFreshInstall() throws Exception {
        File pending = new File(file.getPath() + ".new");
        byte[] interrupted = {1, 2, 3};
        Files.write(pending.toPath(), interrupted);
        assertEquals(Wire.STORAGE_UNAVAILABLE,
                open().authorize(OWNER_UID, RESOURCE, Wire.WRITE, true));
        assertArrayEquals(interrupted, Files.readAllBytes(pending.toPath()));
    }

    private static byte[] entries(int owners, int grants, int rights, boolean trailing)
            throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (DataOutputStream out = new DataOutputStream(bytes)) {
            out.writeInt(0x47444131);
            out.writeInt(1);
            out.writeInt(owners);
            for (int i = 0; i < owners; i++) {
                out.writeUTF(RESOURCE.id());
                out.writeUTF(ORIGINAL_SIGNATURE);
            }
            out.writeInt(grants);
            for (int i = 0; i < grants; i++) {
                out.writeUTF(RESOURCE.id() + "|" + TARGET);
                out.writeUTF("b".repeat(64));
                out.writeInt(rights);
            }
            if (trailing) out.writeByte(1);
        }
        return bytes.toByteArray();
    }
}
