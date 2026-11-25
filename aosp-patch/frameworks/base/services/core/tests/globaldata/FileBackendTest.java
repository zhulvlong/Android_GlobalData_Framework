package com.android.server.globaldata;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import androidx.test.platform.app.InstrumentationRegistry;
import com.oem.globaldata.ipc.Wire;
import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.zip.CRC32;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

public final class FileBackendTest {
    @Rule public final TemporaryFolder temp = new TemporaryFolder(
            InstrumentationRegistry.getInstrumentation().getTargetContext().getCacheDir());

    private static final StoredValue EMPTY = new StoredValue("s", 1, "r", new byte[0], 0);
    private File directory;
    private FileBackend backend;

    @Before public void setUp() throws IOException {
        directory = temp.newFolder();
        backend = newBackend();
    }

    private FileBackend newBackend() {
        return new FileBackend(key -> directory);
    }

    private static StoreAddress key(String ownerSuffix, String key) {
        return new StoreAddress(Wire.FILE, Wire.USER_DE, 0, "com.example.owner." + ownerSuffix, key);
    }

    // Build a v1 fixture directly so the boundary test does not rewrite 8191 whole snapshots.
    private static byte[] snapshot(int count, int payloadSize, boolean duplicate,
            boolean invalidRevision) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        DataOutputStream out = new DataOutputStream(bytes);
        out.writeInt(0x47444631);
        out.writeInt(1);
        out.writeLong(count);
        out.writeInt(count);
        byte[] payload = new byte[payloadSize];
        for (int i = 0; i < count; i++) {
            out.writeUTF("k" + (duplicate ? 0 : i));
            out.writeUTF("s");
            out.writeInt(1);
            out.writeUTF("r");
            out.writeLong(invalidRevision ? count + 1 : i + 1);
            out.writeInt(payload.length);
            out.write(payload);
        }
        CRC32 crc = new CRC32();
        crc.update(bytes.toByteArray());
        out.writeLong(crc.getValue());
        return bytes.toByteArray();
    }

    @Test public void entryLimitMatchesReloadAndDoesNotBlockUpdatesOrDeletes() throws Exception {
        StoreAddress last = key("ns", "k8191"), extra = key("ns", "extra");
        File file = backend.file(last).getBaseFile();
        Files.write(file.toPath(), snapshot(8191, 0, false, false));
        assertEquals(Wire.OK, backend.write(last, EMPTY, Wire.IF_REVISION, 8191).status);
        byte[] committed = Files.readAllBytes(file.toPath());
        Backend.Change rejected = backend.write(extra, EMPTY, Wire.UPSERT, -1);
        assertEquals(Wire.QUOTA_EXCEEDED, rejected.status);
        assertEquals(0, rejected.operation);
        assertArrayEquals(committed, Files.readAllBytes(file.toPath()));

        FileBackend reloaded = newBackend();
        assertEquals(8192, reloaded.read(last).revision);
        assertEquals(8192, reloaded.read(extra).revision);
        assertNull(reloaded.read(extra).value);
        assertEquals(Wire.OK, reloaded.write(last, EMPTY, Wire.IF_REVISION, 8192).status);
        assertEquals(Wire.OK, reloaded.delete(last, 8193).status);
        assertEquals(8194, newBackend().read(last).revision);
        assertEquals(Wire.OK, reloaded.write(extra, EMPTY, Wire.IF_REVISION, 8194).status);
    }

    @Test public void byteQuotaDoesNotChangeCommittedSnapshot() throws Exception {
        StoreAddress extra = key("ns", "extra");
        File file = backend.file(extra).getBaseFile();
        byte[] committed = snapshot(15, 64 * 1024, false, false);
        Files.write(file.toPath(), committed);

        StoredValue large = new StoredValue("s", 1, "r", new byte[64 * 1024], 0);
        Backend.Change rejected = backend.write(extra, large, Wire.UPSERT, -1);
        assertEquals(Wire.QUOTA_EXCEEDED, rejected.status);
        assertEquals(0, rejected.operation);
        assertArrayEquals(committed, Files.readAllBytes(file.toPath()));
        assertNull(newBackend().read(extra).value);
        assertEquals(15, newBackend().read(extra).revision);
    }

    @Test public void revisionOverflowCannotCommitAnUnreadableSnapshot() throws Exception {
        StoreAddress key = key("ns", "k0");
        File file = backend.file(key).getBaseFile();
        byte[] committed = snapshot(1, 0, false, false);
        ByteBuffer.wrap(committed).putLong(8, Long.MAX_VALUE);
        CRC32 crc = new CRC32();
        int trailer = committed.length - Long.BYTES;
        crc.update(committed, 0, trailer);
        ByteBuffer.wrap(committed).putLong(trailer, crc.getValue());
        Files.write(file.toPath(), committed);

        assertEquals(1, backend.read(key).revision);
        assertThrows(ArithmeticException.class, () -> backend.delete(key, 1));
        assertArrayEquals(committed, Files.readAllBytes(file.toPath()));
        assertEquals(1, newBackend().read(key).revision);
        assertEquals(Long.MAX_VALUE, newBackend().read(key("ns", "missing")).revision);
    }

    @Test public void lruBoundsCountAndRetainsHighWaterOnReload() throws Exception {
        AtomicInteger loads = new AtomicInteger();
        FileBackend cache = new FileBackend(key -> {
            loads.incrementAndGet();
            return directory;
        });
        StoreAddress first = key("ns0", "k0"), second = key("ns1", "k0");
        for (int i = 0; i < 16; i++) {
            assertEquals(Wire.OK, cache.write(key("ns" + i, "k0"), EMPTY, Wire.UPSERT, -1).status);
        }
        assertEquals(Wire.OK, cache.delete(second, 1).status);
        cache.read(first);
        cache.read(key("ns2", "k0"));
        for (int i = 3; i < 16; i++) cache.read(key("ns" + i, "k0"));
        assertEquals(Wire.OK, cache.write(key("ns16", "k0"), EMPTY, Wire.UPSERT, -1).status);
        loads.set(0);
        assertEquals(1, cache.read(first).revision);
        assertEquals(0, loads.get());
        Backend.Snapshot reloaded = cache.read(second);
        assertEquals(1, loads.get());
        assertNull(reloaded.value);
        assertEquals(2, reloaded.revision);
        assertEquals(Wire.CONFLICT, cache.write(second, EMPTY, Wire.IF_REVISION, 0).status);
    }

    @Test public void cacheAlsoEvictsOnByteBudget() throws Exception {
        AtomicInteger loads = new AtomicInteger();
        FileBackend cache = new FileBackend(key -> {
            loads.incrementAndGet();
            return directory;
        });
        byte[] large = snapshot(15, 64 * 1024, false, false);
        for (int i = 0; i < 9; i++) {
            StoreAddress address = key("large" + i, "k14");
            Files.write(backend.file(address).getBaseFile().toPath(), large);
            assertEquals(15, cache.read(address).revision);
        }
        loads.set(0);
        assertEquals(15, cache.read(key("large8", "k14")).revision);
        assertEquals(0, loads.get());
        Backend.Snapshot reloaded = cache.read(key("large0", "k14"));
        assertEquals(1, loads.get());
        assertEquals(15, reloaded.revision);
        assertArrayEquals(new byte[64 * 1024], reloaded.value.payload);
    }

    @Test public void corruptFilesAndIoFailureDoNotReplaceCommittedData() throws Exception {
        StoreAddress address = key("ns", "k0");
        File file = backend.file(address).getBaseFile();
        byte[] badCrc = snapshot(1, 0, false, false);
        badCrc[badCrc.length - 1] ^= 1;
        for (byte[] bytes : new byte[][]{
                new byte[]{1, 2}, badCrc, snapshot(2, 0, true, false),
                snapshot(1, 0, false, true), snapshot(8193, 0, false, false)}) {
            Files.write(file.toPath(), bytes);
            assertThrows(IllegalStateException.class, () -> backend.read(address));
            assertThrows(IllegalStateException.class,
                    () -> backend.write(address, EMPTY, Wire.UPSERT, -1));
            assertArrayEquals(bytes, Files.readAllBytes(file.toPath()));
        }
        Files.write(file.toPath(), snapshot(1, 0, false, false));
        assertEquals(1, backend.read(address).revision);
        assertTrue(new File(file.getPath() + ".new").mkdir());
        byte[] committed = Files.readAllBytes(file.toPath());
        assertThrows(IllegalStateException.class,
                () -> backend.write(address, EMPTY, Wire.IF_REVISION, 1));
        assertArrayEquals(committed, Files.readAllBytes(file.toPath()));
        assertEquals(1, backend.read(address).revision);
        assertEquals(1, newBackend().read(address).revision);
    }
}
