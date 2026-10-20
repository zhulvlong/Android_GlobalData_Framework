package com.android.server.globaldata;

import com.oem.globaldata.ipc.Wire;

interface Backend {
    Snapshot read(StoreAddress key);
    Change write(StoreAddress key, StoredValue value, int mode, long expected);
    Change delete(StoreAddress key, long expected);

    final class Snapshot {
        final StoredValue value;
        final long revision;
        Snapshot(StoredValue value, long revision) { this.value = value; this.revision = revision; }
    }
    final class Change {
        final int status, operation;
        final long revision;
        Change(int status, int operation, long revision) {
            this.status = status; this.operation = operation; this.revision = revision;
        }
    }
    static int precondition(StoredValue old, long absentRevision, int mode, long expected) {
        if (mode == Wire.UPSERT) return Wire.OK;
        if (mode == Wire.IF_ABSENT) return old == null ? Wire.OK : Wire.CONFLICT;
        if (mode == Wire.IF_REVISION)
            return expected == (old == null ? absentRevision : old.revision) ? Wire.OK : Wire.CONFLICT;
        return Wire.BAD_REQUEST;
    }
}
