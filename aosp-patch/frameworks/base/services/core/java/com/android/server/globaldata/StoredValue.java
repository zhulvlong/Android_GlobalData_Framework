package com.android.server.globaldata;

import android.os.Bundle;
import com.oem.globaldata.ipc.Wire;
import java.util.Arrays;

final class StoredValue {
    final String schemaId, encoding;
    final int schemaVersion;
    final byte[] payload;
    final long revision;
    StoredValue(String schemaId, int schemaVersion, String encoding, byte[] payload, long revision) {
        this.schemaId = schemaId; this.schemaVersion = schemaVersion; this.encoding = encoding;
        this.payload = Arrays.copyOf(payload, payload.length); this.revision = revision;
    }
    static StoredValue parse(Bundle b) {
        if (b == null) throw new IllegalArgumentException("missing value");
        String schema = b.getString(Wire.SCHEMA_ID), encoding = b.getString(Wire.ENCODING);
        byte[] payload = b.getByteArray(Wire.PAYLOAD);
        int version = b.getInt(Wire.SCHEMA_VERSION);
        if (!StoreAddress.name(schema, 128) || !StoreAddress.name(encoding, 64)
                || version < 1 || payload == null || payload.length > Wire.MAX_PAYLOAD)
            throw new IllegalArgumentException("invalid encoded value");
        return new StoredValue(schema, version, encoding, payload, 0);
    }
    StoredValue withRevision(long next) {
        return new StoredValue(schemaId, schemaVersion, encoding, payload, next);
    }
    Bundle wire() {
        Bundle b = new Bundle(); b.putString(Wire.SCHEMA_ID, schemaId);
        b.putInt(Wire.SCHEMA_VERSION, schemaVersion); b.putString(Wire.ENCODING, encoding);
        b.putByteArray(Wire.PAYLOAD, Arrays.copyOf(payload, payload.length)); return b;
    }
}
