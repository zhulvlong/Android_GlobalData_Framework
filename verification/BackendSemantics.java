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
                "com.example.owner", "key");
        StoreAddress otherUser = new StoreAddress(Wire.MEMORY, Wire.USER_CE, 10,
                "com.example.owner", "key");
        StoreAddress otherOwner = new StoreAddress(Wire.MEMORY, Wire.USER_CE, 0,
                "com.example.other", "key");
        StoredValue value = new StoredValue("primitive.string", 1, "utf8-v1", new byte[]{1}, 0);
        expect(memory.read(first).revision == 0, "new owner revision");
        Backend.Change created = memory.write(first, value, Wire.IF_ABSENT, -1);
        expect(created.status == Wire.OK && created.revision == 1, "create");
        expect(memory.write(first, value, Wire.IF_ABSENT, -1).status == Wire.CONFLICT,
                "if absent must fail on existing value");
        expect(memory.read(otherUser).value == null, "user isolation");
        expect(memory.read(otherOwner).value == null, "owner isolation");
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
        recordAndPayloadQuotas();
        ownerHighWater();
        System.out.println("MemoryBackend CAS/isolation/quotas: PASS");
    }
    private static StoreAddress key(int scope, int user, String owner, String key) {
        return new StoreAddress(Wire.MEMORY, scope, user, owner, key);
    }

    private static void recordAndPayloadQuotas() {
        MemoryBackend memory = new MemoryBackend();
        String owner = "com.example.owner";
        StoredValue empty = new StoredValue("test.schema", 1, "raw", new byte[0], 0);
        for (int i = 0; i < 4096; i++) {
            expect(memory.write(key(Wire.USER_DE, 0, owner, "k" + i),
                    empty, Wire.UPSERT, -1).status == Wire.OK, "empty key at " + i);
        }
        StoreAddress extra = key(Wire.USER_DE, 0, owner, "extra");
        Backend.Change rejected = memory.write(extra, empty, Wire.UPSERT, -1);
        expect(rejected.status == Wire.QUOTA_EXCEEDED && rejected.operation == 0
                && memory.read(extra).revision == 4096, "reject without advancing revision");

        StoreAddress existing = key(Wire.USER_DE, 0, owner, "k0");
        StoredValue large = new StoredValue("test.schema", 1, "raw", new byte[64 * 1024], 0);
        expect(memory.write(existing, large, Wire.IF_REVISION, 1).status == Wire.OK,
                "replacement at count limit");
        for (int i = 1; i < 64; i++) {
            expect(memory.write(key(Wire.USER_DE, 0, owner, "k" + i),
                    large, Wire.UPSERT, -1).status == Wire.OK, "payload up to 4 MiB");
        }
        StoreAddress small = key(Wire.USER_DE, 0, owner, "k64");
        expect(memory.write(small, large, Wire.UPSERT, -1).status == Wire.QUOTA_EXCEEDED,
                "payload quota applies to replacements");
        expect(memory.delete(existing, -1).status == Wire.OK, "delete frees quota");
        expect(memory.write(extra, large, Wire.IF_ABSENT, -1).status == Wire.OK,
                "reuse record and payload quota after delete");
        expect(memory.write(existing, empty, Wire.IF_REVISION, 1).status == Wire.CONFLICT,
                "old value CAS cannot resurrect after deletion");

        for (StoreAddress isolated : new StoreAddress[]{
                key(Wire.USER_CE, 0, owner, "extra"),
                key(Wire.USER_DE, 10, owner, "extra"),
                key(Wire.USER_DE, 0, "com.example.other", "extra")}) {
            expect(memory.read(isolated).value == null, "scope/user/owner address isolation");
            expect(memory.write(isolated, large, Wire.IF_ABSENT, -1).status == Wire.OK,
                    "scope/user/owner quota isolation");
        }
    }

    private static void ownerHighWater() {
        MemoryBackend memory = new MemoryBackend();
        String owner = "com.example.owner";
        StoredValue empty = new StoredValue("test.schema", 1, "raw", new byte[0], 0);
        StoreAddress first = key(Wire.USER_DE, 0, owner, "first");
        StoreAddress second = key(Wire.USER_DE, 0, owner, "second");
        expect(memory.read(second).revision == 0, "fresh owner");
        expect(memory.delete(second, -1).status == Wire.NOT_FOUND, "delete missing key");
        expect(memory.write(second, empty, Wire.IF_REVISION, 1).status == Wire.CONFLICT,
                "failed CAS does not advance owner high water");
        expect(memory.write(first, empty, Wire.IF_REVISION, 0).status == Wire.OK, "create");
        expect(memory.read(second).revision == 1, "absent key sees the owner high water");
        expect(memory.write(second, empty, Wire.IF_REVISION, 0).status == Wire.CONFLICT,
                "write to a different key invalidates old absent snapshot");
        expect(memory.delete(first, 1).status == Wire.OK, "delete last value");
        expect(memory.read(second).revision == 2, "deleting last key retains owner high water");
        expect(memory.write(second, empty, Wire.IF_REVISION, 2).status == Wire.OK,
                "fresh absent snapshot can create");
    }
}
