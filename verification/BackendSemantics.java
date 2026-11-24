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
        recordAndPayloadQuotas();
        namespaceQuota();
        System.out.println("MemoryBackend CAS/isolation/quotas: PASS");
    }
    private static StoreAddress key(int scope, int user, String owner, String ns, String key) {
        return new StoreAddress(Wire.MEMORY, scope, user, owner, ns, key);
    }

    private static void recordAndPayloadQuotas() {
        MemoryBackend memory = new MemoryBackend();
        String owner = "com.example.owner";
        StoredValue empty = new StoredValue("test.schema", 1, "raw", new byte[0], 0);
        for (int i = 0; i < 4096; i++) {
            expect(memory.write(key(Wire.USER_DE, 0, owner, "ns", "k" + i),
                    empty, Wire.UPSERT, -1).status == Wire.OK, "empty key at " + i);
        }
        StoreAddress extra = key(Wire.USER_DE, 0, owner, "ns", "extra");
        Backend.Change rejected = memory.write(extra, empty, Wire.UPSERT, -1);
        expect(rejected.status == Wire.QUOTA_EXCEEDED && rejected.operation == 0
                && memory.read(extra).revision == 4096, "reject without advancing revision");

        StoreAddress existing = key(Wire.USER_DE, 0, owner, "ns", "k0");
        StoredValue large = new StoredValue("test.schema", 1, "raw", new byte[64 * 1024], 0);
        expect(memory.write(existing, large, Wire.IF_REVISION, 1).status == Wire.OK,
                "replacement at count limit");
        for (int i = 1; i < 64; i++) {
            expect(memory.write(key(Wire.USER_DE, 0, owner, "ns", "k" + i),
                    large, Wire.UPSERT, -1).status == Wire.OK, "payload up to 4 MiB");
        }
        StoreAddress small = key(Wire.USER_DE, 0, owner, "ns", "k64");
        expect(memory.write(small, large, Wire.UPSERT, -1).status == Wire.QUOTA_EXCEEDED,
                "payload quota applies to replacements");
        expect(memory.delete(existing, -1).status == Wire.OK, "delete frees quota");
        expect(memory.write(extra, large, Wire.IF_ABSENT, -1).status == Wire.OK,
                "reuse record and payload quota after delete");
        expect(memory.write(existing, empty, Wire.IF_REVISION, 1).status == Wire.CONFLICT,
                "old value CAS cannot resurrect after deletion");

        for (StoreAddress isolated : new StoreAddress[]{
                key(Wire.USER_CE, 0, owner, "ns", "extra"),
                key(Wire.USER_DE, 10, owner, "ns", "extra"),
                key(Wire.USER_DE, 0, "com.example.other", "ns", "extra")}) {
            expect(memory.read(isolated).value == null, "scope/user/owner address isolation");
            expect(memory.write(isolated, large, Wire.IF_ABSENT, -1).status == Wire.OK,
                    "scope/user/owner quota isolation");
        }
    }

    private static void namespaceQuota() {
        MemoryBackend memory = new MemoryBackend();
        String owner = "com.example.owner";
        StoredValue empty = new StoredValue("test.schema", 1, "raw", new byte[0], 0);
        for (int i = 0; i < 128; i++) {
            StoreAddress missing = key(Wire.USER_DE, 0, owner, "missing" + i, "key");
            expect(memory.read(missing).revision == 0, "fresh namespace");
            expect(memory.delete(missing, -1).status == Wire.NOT_FOUND, "delete missing namespace");
            expect(memory.write(missing, empty, Wire.IF_REVISION, 1).status == Wire.CONFLICT,
                    "failed CAS cannot allocate a namespace");
        }
        for (int i = 0; i < 64; i++) {
            StoreAddress address = key(Wire.USER_DE, 0, owner, "ns" + i, "key");
            expect(memory.write(address, empty, Wire.IF_REVISION, 0).status == Wire.OK, "create");
            expect(memory.delete(address, 1).status == Wire.OK, "delete last value");
            expect(memory.read(address).revision == 2, "retain namespace high water");
        }
        StoreAddress extra = key(Wire.USER_DE, 0, owner, "extra", "key");
        expect(memory.write(extra, empty, Wire.UPSERT, -1).status == Wire.QUOTA_EXCEEDED,
                "empty namespaces retain their version slots");
        StoreAddress first = key(Wire.USER_DE, 0, owner, "ns0", "key");
        expect(memory.write(first, empty, Wire.IF_REVISION, 0).status == Wire.CONFLICT,
                "initial absent snapshot cannot pass after create/delete");
        expect(memory.write(first, empty, Wire.IF_REVISION, 2).status == Wire.OK,
                "existing namespace remains writable at quota");
    }

}
