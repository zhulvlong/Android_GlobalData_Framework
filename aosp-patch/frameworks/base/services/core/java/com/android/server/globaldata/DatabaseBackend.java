package com.android.server.globaldata;

import android.content.ContentValues;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.os.Environment;
import android.os.SELinux;
import com.oem.globaldata.ipc.Wire;
import java.io.File;
import java.util.HashMap;
import java.util.Map;

/** Per-scope/user SQLite KV. Namespace high-water makes physical delete ABA-safe. */
final class DatabaseBackend implements Backend {
    private static final String TAG = "DatabaseBackend";

    private final Map<String, SQLiteDatabase> dbs = new HashMap<>();
    private SQLiteDatabase db(StoreAddress key) {
        String id = key.scope + "|" + key.userId;
        SQLiteDatabase db = dbs.get(id);
        if (db != null && db.isOpen()) {
            TLog.d(TAG, "connection reused scope=" + key.scope + " user=" + key.userId);
            return db;
        }
        TLog.d(TAG, "opening scope=" + key.scope + " user=" + key.userId);
        File parent = key.scope == Wire.USER_CE
                ? Environment.getDataSystemCeDirectory(key.userId)
                : Environment.getDataSystemDeDirectory(key.userId);
        File dir = new File(parent, "globaldata/db");
        if (!dir.exists()) {
            if (!dir.mkdirs() || !SELinux.restoreconRecursive(dir))
                throw new IllegalStateException("cannot create database dir");
        }
        db = SQLiteDatabase.openOrCreateDatabase(new File(dir, "globaldata-v1.db"), null);
        db.enableWriteAheadLogging(); db.execSQL("PRAGMA synchronous=FULL");
        db.execSQL("CREATE TABLE IF NOT EXISTS nsrev (owner TEXT NOT NULL, ns TEXT NOT NULL, "
                + "rev INTEGER NOT NULL, PRIMARY KEY(owner,ns))");
        db.execSQL("CREATE TABLE IF NOT EXISTS kv (owner TEXT NOT NULL, ns TEXT NOT NULL, "
                + "k TEXT NOT NULL, schema_id TEXT NOT NULL, schema_ver INTEGER NOT NULL, "
                + "encoding TEXT NOT NULL, payload BLOB NOT NULL, rev INTEGER NOT NULL, "
                + "PRIMARY KEY(owner,ns,k))");
        dbs.put(id, db); return db;
    }
    private static long highWater(SQLiteDatabase db, StoreAddress key) {
        try (Cursor c = db.rawQuery("SELECT rev FROM nsrev WHERE owner=? AND ns=?",
                new String[]{key.owner, key.namespace})) {
            return c.moveToFirst() ? c.getLong(0) : 0;
        }
    }
    private static StoredValue value(SQLiteDatabase db, StoreAddress key) {
        try (Cursor c = db.rawQuery("SELECT schema_id,schema_ver,encoding,payload,rev "
                        + "FROM kv WHERE owner=? AND ns=? AND k=?",
                new String[]{key.owner, key.namespace, key.key})) {
            return c.moveToFirst() ? new StoredValue(c.getString(0), c.getInt(1),
                    c.getString(2), c.getBlob(3), c.getLong(4)) : null;
        }
    }
    private static void setHighWater(SQLiteDatabase db, StoreAddress key, long revision) {
        ContentValues values = new ContentValues(); values.put("owner", key.owner);
        values.put("ns", key.namespace); values.put("rev", revision);
        if (db.insertWithOnConflict("nsrev", null, values,
                SQLiteDatabase.CONFLICT_REPLACE) < 0)
            throw new IllegalStateException("revision insert failed");
    }
    @Override public synchronized Snapshot read(StoreAddress key) {
        SQLiteDatabase db = db(key); StoredValue value = value(db, key);
        long revision = value == null ? highWater(db, key) : value.revision;
        TLog.d(TAG, "read " + TLog.address(key) + " present=" + (value != null)
                + " revision=" + revision);
        return new Snapshot(value, revision);
    }
    @Override public synchronized Change write(StoreAddress key, StoredValue proposed, int mode, long expected) {
        SQLiteDatabase db = db(key); db.beginTransaction();
        try {
            StoredValue old = value(db, key); long high = highWater(db, key);
            int status = Backend.precondition(old, high, mode, expected);
            if (status != Wire.OK) {
                TLog.d(TAG, "write precondition " + TLog.address(key) + " status=" + status);
                return new Change(status, 0, old == null ? high : old.revision);
            }
            // Count only current payloads; transaction serializes concurrent writers.
            try (Cursor c = db.rawQuery("SELECT COALESCE(SUM(LENGTH(payload)),0) FROM kv WHERE owner=?",
                    new String[]{key.owner})) {
                c.moveToFirst();
                long used = c.getLong(0) + proposed.payload.length - (old == null ? 0 : old.payload.length);
                if (used > 16L * 1024 * 1024) {
                    TLog.d(TAG, "write quota " + TLog.address(key) + " bytes=" + used);
                    return new Change(Wire.QUOTA_EXCEEDED, 0, high);
                }
            }
            long revision = high + 1;
            ContentValues values = new ContentValues(); values.put("owner", key.owner);
            values.put("ns", key.namespace); values.put("k", key.key);
            values.put("schema_id", proposed.schemaId); values.put("schema_ver", proposed.schemaVersion);
            values.put("encoding", proposed.encoding); values.put("payload", proposed.payload);
            values.put("rev", revision);
            if (db.insertWithOnConflict("kv", null, values,
                    SQLiteDatabase.CONFLICT_REPLACE) < 0)
                throw new IllegalStateException("value insert failed");
            setHighWater(db, key, revision); db.setTransactionSuccessful();
            TLog.d(TAG, "write transaction marked successful " + TLog.address(key)
                    + " revision=" + revision);
            return new Change(Wire.OK, old == null ? Wire.CREATE_OP : Wire.UPDATE_OP, revision);
        } finally {
            db.endTransaction();
            TLog.d(TAG, "write transaction ended " + TLog.address(key));
        }
    }
    @Override public synchronized Change delete(StoreAddress key, long expected) {
        SQLiteDatabase db = db(key); db.beginTransaction();
        try {
            StoredValue old = value(db, key); long high = highWater(db, key);
            if (old == null) {
                TLog.d(TAG, "delete absent " + TLog.address(key) + " revision=" + high);
                return new Change(Wire.NOT_FOUND, 0, high);
            }
            if (expected >= 0 && expected != old.revision) {
                TLog.d(TAG, "delete conflict " + TLog.address(key) + " revision=" + old.revision);
                return new Change(Wire.CONFLICT, 0, old.revision);
            }
            long revision = high + 1;
            if (db.delete("kv", "owner=? AND ns=? AND k=?",
                    new String[]{key.owner, key.namespace, key.key}) != 1)
                throw new IllegalStateException("value delete failed");
            setHighWater(db, key, revision); db.setTransactionSuccessful();
            TLog.d(TAG, "delete transaction marked successful " + TLog.address(key)
                    + " revision=" + revision);
            return new Change(Wire.OK, Wire.DELETE_OP, revision);
        } finally {
            db.endTransaction();
            TLog.d(TAG, "delete transaction ended " + TLog.address(key));
        }
    }
}
