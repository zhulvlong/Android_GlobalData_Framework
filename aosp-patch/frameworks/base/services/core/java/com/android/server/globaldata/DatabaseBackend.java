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

/**
 * SQLite 键值后端。每个 scope/user 使用一个数据库；kv 保存当前记录，
 * nsrev 保存 owner/namespace 的版本高水位。物理删除 Key 后仍推进高水位，
 * 使旧的“不存在”快照无法再次通过 CAS。
 * 分库保持 DE/CE 加密边界和用户隔离；数据行与版本行必须在同一事务内改变，
 * 否则重启后可能出现值已更新、CAS 版本却还是旧值的状态。
 */
final class DatabaseBackend implements Backend {
    /** 日志二级标识。 */
    private static final String TAG = "DatabaseBackend";

    /** scope|userId -> 已打开的 SQLite 连接，由 synchronized 入口串行访问。 */
    private final Map<String, SQLiteDatabase> dbs = new HashMap<>();
    /**
     * 按作用域和用户打开数据库，而非把所有用户放进同一文件。
     * WAL 与 FULL 同步兼顾日志式写入和提交持久性，首次打开时创建版本表与值表。
     */
    private SQLiteDatabase db(StoreAddress key) {
        String id = key.scope + "|" + key.userId;
        // 1. 同一用户/作用域复用连接，避免每个 Key 都重复打开 SQLite 文件。
        SQLiteDatabase db = dbs.get(id);
        if (db != null && db.isOpen()) {
            TLog.d(TAG, "connection reused scope=" + key.scope + " user=" + key.userId);
            return db;
        }
        TLog.d(TAG, "opening scope=" + key.scope + " user=" + key.userId);
        // 2. 用 DE/CE 系统目录隔离用户与解锁状态；新目录需要正确 SELinux 标签。
        File parent = key.scope == Wire.USER_CE
                ? Environment.getDataSystemCeDirectory(key.userId)
                : Environment.getDataSystemDeDirectory(key.userId);
        File dir = new File(parent, "globaldata/db");
        if (!dir.exists()) {
            if (!dir.mkdirs() || !SELinux.restoreconRecursive(dir))
                throw new IllegalStateException("cannot create database dir");
        }
        // 3. 初始化数据库和表后才缓存连接，避免后续请求复用半初始化状态。
        db = SQLiteDatabase.openOrCreateDatabase(new File(dir, "globaldata-v1.db"), null);
        db.enableWriteAheadLogging(); db.execSQL("PRAGMA synchronous=FULL");
        // 如果版本只存在 kv 行，删除最后一个 Key 后旧的“不存在”CAS 会误以为版本又归零。
        db.execSQL("CREATE TABLE IF NOT EXISTS nsrev (owner TEXT NOT NULL, ns TEXT NOT NULL, "
                + "rev INTEGER NOT NULL, PRIMARY KEY(owner,ns))");
        db.execSQL("CREATE TABLE IF NOT EXISTS kv (owner TEXT NOT NULL, ns TEXT NOT NULL, "
                + "k TEXT NOT NULL, schema_id TEXT NOT NULL, schema_ver INTEGER NOT NULL, "
                + "encoding TEXT NOT NULL, payload BLOB NOT NULL, rev INTEGER NOT NULL, "
                + "PRIMARY KEY(owner,ns,k))");
        dbs.put(id, db); return db;
    }
    /** 读取 namespace 的最新成功变更版本；尚无变更时为 0。 */
    private static long highWater(SQLiteDatabase db, StoreAddress key) {
        try (Cursor c = db.rawQuery("SELECT rev FROM nsrev WHERE owner=? AND ns=?",
                new String[]{key.owner, key.namespace})) {
            return c.moveToFirst() ? c.getLong(0) : 0;
        }
    }
    /** 从 kv 读取单条编码记录及其 revision；不存在时为 null。 */
    private static StoredValue value(SQLiteDatabase db, StoreAddress key) {
        try (Cursor c = db.rawQuery("SELECT schema_id,schema_ver,encoding,payload,rev "
                        + "FROM kv WHERE owner=? AND ns=? AND k=?",
                new String[]{key.owner, key.namespace, key.key})) {
            return c.moveToFirst() ? new StoredValue(c.getString(0), c.getInt(1),
                    c.getString(2), c.getBlob(3), c.getLong(4)) : null;
        }
    }
    /** 与 kv 更新放进同一事务，避免值和版本分别落盘后出现不一致。 */
    private static void setHighWater(SQLiteDatabase db, StoreAddress key, long revision) {
        ContentValues values = new ContentValues(); values.put("owner", key.owner);
        values.put("ns", key.namespace); values.put("rev", revision);
        if (db.insertWithOnConflict("nsrev", null, values,
                SQLiteDatabase.CONFLICT_REPLACE) < 0)
            throw new IllegalStateException("revision insert failed");
    }
    /** 返回当前记录；不存在时仍返回 nsrev 高水位供条件写入使用。 */
    @Override public synchronized Snapshot read(StoreAddress key) {
        SQLiteDatabase db = db(key); StoredValue value = value(db, key);
        long revision = value == null ? highWater(db, key) : value.revision;
        TLog.d(TAG, "read " + TLog.address(key) + " present=" + (value != null)
                + " revision=" + revision);
        return new Snapshot(value, revision);
    }
    /**
     * 单事务内完成旧值读取、CAS、owner 配额检查、kv 更新和高水位推进。
     * 这样并发条件写入不会分别通过同一个旧版本，提前返回的冲突/配额错误也会回滚。
     * setTransactionSuccessful 只标记可提交，真正提交发生在 finally 的 endTransaction。
     */
    @Override public synchronized Change write(StoreAddress key, StoredValue proposed, int mode, long expected) {
        SQLiteDatabase db = db(key); db.beginTransaction();
        try {
            // 1. 在同一事务快照中读旧值和高水位，再判断条件；不能先读后开事务。
            StoredValue old = value(db, key); long high = highWater(db, key);
            int status = Backend.precondition(old, high, mode, expected);
            if (status != Wire.OK) {
                TLog.d(TAG, "write precondition " + TLog.address(key) + " status=" + status);
                return new Change(status, 0, old == null ? high : old.revision);
            }
            // 2. 只计算当前 payload 并替换旧值大小，限制此 owner 的磁盘占用。
            try (Cursor c = db.rawQuery("SELECT COALESCE(SUM(LENGTH(payload)),0) FROM kv WHERE owner=?",
                    new String[]{key.owner})) {
                c.moveToFirst();
                long used = c.getLong(0) + proposed.payload.length - (old == null ? 0 : old.payload.length);
                if (used > 16L * 1024 * 1024) {
                    TLog.d(TAG, "write quota " + TLog.address(key) + " bytes=" + used);
                    return new Change(Wire.QUOTA_EXCEEDED, 0, high);
                }
            }
            // 3. 值行与 namespace 高水位必须同一事务提交，CAS 才不会看到不一致版本。
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
            // 4. 所有路径都会结束事务；未标记成功的冲突、配额和异常自动回滚。
            db.endTransaction();
            TLog.d(TAG, "write transaction ended " + TLog.address(key));
        }
    }
    /**
     * 删除 kv 行也推进 nsrev，防止删除后出现版本 ABA；冲突或不存在时不提交，
     * 客户端可据返回的现有 revision 决定是否重新读取并重试。
     */
    @Override public synchronized Change delete(StoreAddress key, long expected) {
        SQLiteDatabase db = db(key); db.beginTransaction();
        try {
            // 1. 在事务中确认记录存在且条件版本匹配，失败时不推进高水位。
            StoredValue old = value(db, key); long high = highWater(db, key);
            if (old == null) {
                TLog.d(TAG, "delete absent " + TLog.address(key) + " revision=" + high);
                return new Change(Wire.NOT_FOUND, 0, high);
            }
            if (expected >= 0 && expected != old.revision) {
                TLog.d(TAG, "delete conflict " + TLog.address(key) + " revision=" + old.revision);
                return new Change(Wire.CONFLICT, 0, old.revision);
            }
            // 2. 物理删除 kv 行时仍写入新的 nsrev；否则版本无法反映这次删除。
            long revision = high + 1;
            if (db.delete("kv", "owner=? AND ns=? AND k=?",
                    new String[]{key.owner, key.namespace, key.key}) != 1)
                throw new IllegalStateException("value delete failed");
            setHighWater(db, key, revision); db.setTransactionSuccessful();
            TLog.d(TAG, "delete transaction marked successful " + TLog.address(key)
                    + " revision=" + revision);
            return new Change(Wire.OK, Wire.DELETE_OP, revision);
        } finally {
            // 3. endTransaction 才真正提交成功标记的删除；提前返回则回滚。
            db.endTransaction();
            TLog.d(TAG, "delete transaction ended " + TLog.address(key));
        }
    }
}
