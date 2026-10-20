package com.android.server.globaldata;

import com.oem.globaldata.ipc.Wire;

/** Runs the actual MemoryBackend/CAS code on a host JVM without an Android device. */
public final class BackendSemantics {
    private static void expect(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
    public static void main(String[] args) {
        MemoryBackend memory = new MemoryBackend();
        StoreAddress first = new StoreAddress(Wire.MEMORY, Wire.USER_CE, 0,
                "com.example.owner", "share.com.example.owner", "key");
        StoreAddress otherUser = new StoreAddress(Wire.MEMORY, Wire.USER_CE, 10,
                "com.example.owner", "share.com.example.owner", "key");
        StoreAddress otherNamespace = new StoreAddress(Wire.MEMORY, Wire.USER_CE, 0,
                "com.example.owner", "app.com.example.owner", "key");
        StoredValue value = new StoredValue("primitive.string", 1, "utf8-v1", new byte[]{1}, 0);
        expect(memory.read(first).revision == 0, "new namespace revision");
        Backend.Change created = memory.write(first, value, Wire.IF_ABSENT, -1);
        expect(created.status == Wire.OK && created.revision == 1, "create");
        expect(memory.write(first, value, Wire.IF_ABSENT, -1).status == Wire.CONFLICT,
                "if absent must fail on existing value");
        expect(memory.read(otherUser).value == null, "user isolation");
        expect(memory.read(otherNamespace).value == null, "namespace isolation");
        expect(memory.write(first, value, Wire.IF_REVISION, 0).status == Wire.CONFLICT,
                "stale CAS must fail");
        Backend.Change updated = memory.write(first, value, Wire.IF_REVISION, 1);
        expect(updated.status == Wire.OK && updated.revision == 2, "CAS update");
        Backend.Change deleted = memory.delete(first, 2);
        expect(deleted.status == Wire.OK && deleted.revision == 3, "delete bumps revision");
        expect(memory.read(first).revision == 3, "tombstone revision must be readable");
        expect(memory.write(first, value, Wire.IF_REVISION, 2).status == Wire.CONFLICT,
                "pre-delete CAS must never resurrect value");
        expect(memory.write(first, value, Wire.IF_REVISION, 3).status == Wire.OK,
                "fresh absent CAS may create");
        System.out.println("MemoryBackend CAS/isolation: PASS");
    }
}
