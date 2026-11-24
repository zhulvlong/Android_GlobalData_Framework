package com.android.server.globaldata;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertThrows;

import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteException;
import androidx.test.platform.app.InstrumentationRegistry;
import com.oem.globaldata.ipc.Wire;
import java.io.File;
import java.io.IOException;
import java.util.Locale;
import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

public final class DatabaseBackendTest {
    @Rule public final TemporaryFolder temp = new TemporaryFolder(
            InstrumentationRegistry.getInstrumentation().getTargetContext().getCacheDir());

    private static final String OWNER = "com.example.owner", NAMESPACE = "share." + OWNER;
    private static final StoredValue EMPTY = new StoredValue("s", 1, "r", new byte[0], 0);
    private File directory;
    private DatabaseBackend backend;

    @Before public void setUp() throws IOException {
        directory = temp.newFolder();
        backend = openBackend();
    }

    @After public void tearDown() {
        if (backend != null) backend.close();
    }

    private DatabaseBackend openBackend() {
        return new DatabaseBackend(key -> directory);
    }

    private static StoreAddress key(int scope, String owner, String namespace, String key) {
        return new StoreAddress(Wire.DATABASE, scope, 0, owner, namespace, key);
    }

    private static StoreAddress key(String name) {
        return key(Wire.USER_DE, OWNER, NAMESPACE, name);
    }

    private SQLiteDatabase openRaw(StoreAddress key) {
        backend.read(key); // Initialize the production schema before staging a fixture.
        File file = new File(directory, DatabaseBackend.fileName(key));
        return SQLiteDatabase.openDatabase(file.getPath(), null, SQLiteDatabase.OPEN_READWRITE);
    }

    private void seed(int count, String suffix, StoredValue value) {
        try (SQLiteDatabase db = openRaw(key("unused"))) {
            db.beginTransaction();
            try {
                for (int i = 0; i < count; i++) {
                    String id = String.format(Locale.ROOT, "%05d", i) + suffix;
                    db.execSQL("INSERT INTO kv VALUES (?,?,?,?,?,?,?,?)", new Object[]{
                            OWNER, NAMESPACE, id, value.schemaId, value.schemaVersion,
                            value.encoding, value.payload, i + 1});
                }
                db.execSQL("INSERT INTO nsrev VALUES (?,?,?)", new Object[]{OWNER, NAMESPACE, count});
                db.setTransactionSuccessful();
            } finally {
                db.endTransaction();
            }
        }
    }

    @Test public void countLimitSurvivesReopenAndUpdatesAndDeletesStillWork() {
        seed(16383, "", EMPTY);
        StoreAddress last = key("16383"), extra = key("extra");
        assertEquals(Wire.OK, backend.write(last, EMPTY, Wire.IF_REVISION, 16383).status);
        assertEquals(Wire.QUOTA_EXCEEDED, backend.write(extra, EMPTY, Wire.UPSERT, -1).status);
        backend.close();
        backend = openBackend();
        assertEquals(16384, backend.read(last).revision);
        assertEquals(16384, backend.read(extra).revision);
        assertNull(backend.read(extra).value);
        assertEquals(Wire.QUOTA_EXCEEDED, backend.write(extra, EMPTY, Wire.UPSERT, -1).status);
        assertEquals(Wire.OK, backend.write(last, EMPTY, Wire.IF_REVISION, 16384).status);
        assertEquals(Wire.OK, backend.delete(last, 16385).status);
        assertEquals(Wire.CONFLICT, backend.write(last, EMPTY, Wire.IF_REVISION, 16384).status);
        assertEquals(Wire.OK, backend.write(extra, EMPTY, Wire.IF_REVISION, 16386).status);
    }

    @Test public void metadataLimitAppliesToZeroByteValuesAndUsesNetReplacement() {
        StoredValue metadata = new StoredValue("s".repeat(128), 1, "e".repeat(64), new byte[0], 0);
        String suffix = "k".repeat(175);
        // Calculate the fixture position independently from the quota implementation.
        int perRecord = 256 + OWNER.length() + NAMESPACE.length() + 180 + 128 + 64;
        int namespaceBytes = 256 + OWNER.length() + NAMESPACE.length();
        int count = (8 * 1024 * 1024 - namespaceBytes) / perRecord;
        seed(count, suffix, metadata);
        StoreAddress extra = key("extra" + suffix), first = key("00000" + suffix);
        assertEquals(Wire.QUOTA_EXCEEDED, backend.write(extra, metadata, Wire.UPSERT, -1).status);
        assertEquals(Wire.OK, backend.write(first, metadata, Wire.IF_REVISION, 1).status);
        assertEquals(Wire.OK, backend.delete(first, count + 1).status);
        assertEquals(Wire.OK, backend.write(extra, metadata, Wire.IF_REVISION, count + 2).status);
    }

    @Test public void deScopesHaveSeparateDataAndQuota() {
        StoreAddress user = key("same");
        StoreAddress device = key(Wire.DEVICE_DE, OWNER, NAMESPACE, "same");
        assertEquals(Wire.OK, backend.write(user, EMPTY, Wire.UPSERT, -1).status);
        assertNull(backend.read(device).value);
        assertEquals(Wire.OK, backend.write(device, EMPTY, Wire.IF_ABSENT, -1).status);
        for (int i = 1; i < 64; i++) {
            StoreAddress address = key(Wire.USER_DE, OWNER, "ns" + i, "key");
            assertEquals(Wire.OK, backend.write(address, EMPTY, Wire.UPSERT, -1).status);
            assertEquals(Wire.OK, backend.delete(address, 1).status);
        }
        assertEquals(Wire.QUOTA_EXCEEDED, backend.write(key(Wire.USER_DE, OWNER, "extra", "key"),
                EMPTY, Wire.UPSERT, -1).status);
        assertEquals(Wire.OK, backend.write(key(Wire.DEVICE_DE, OWNER, "extra", "key"),
                EMPTY, Wire.UPSERT, -1).status);
    }

    @Test public void failedHighWaterWriteRollsBackValueAndQuota() {
        StoreAddress address = key("key");
        try (SQLiteDatabase db = openRaw(address)) {
            db.execSQL("CREATE TRIGGER reject_revision BEFORE INSERT ON nsrev "
                    + "BEGIN SELECT RAISE(ABORT, 'test rollback'); END");
            assertThrows(SQLiteException.class, () -> backend.write(address, EMPTY, Wire.UPSERT, -1));
            assertNull(backend.read(address).value);
            assertEquals(0, backend.read(address).revision);
            db.execSQL("DROP TRIGGER reject_revision");
        }
        backend.close();
        backend = openBackend();
        assertEquals(0, backend.read(address).revision);
        assertEquals(Wire.OK, backend.write(address, EMPTY, Wire.IF_REVISION, 0).status);
        assertEquals(1, backend.read(address).revision);
    }
}
