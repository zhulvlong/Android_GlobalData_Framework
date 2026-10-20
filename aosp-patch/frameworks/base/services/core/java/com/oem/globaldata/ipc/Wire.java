package com.oem.globaldata.ipc;

/** Frozen names and numeric values for protocol major version 1. */
public final class Wire {
    private Wire() {}
    public static final int PROTOCOL_MAJOR = 1;
    public static final int PROTOCOL_MINOR = 1;
    public static final int MAX_PAYLOAD = 64 * 1024;
    public static final int MEMORY = 1, FILE = 2, DATABASE = 3;
    public static final int DEVICE_DE = 1, USER_DE = 2, USER_CE = 3;
    public static final int UPSERT = 1, IF_ABSENT = 2, IF_REVISION = 3;
    public static final int OK = 0, NOT_FOUND = 1, PERMISSION_DENIED = 2,
            CONFLICT = 3, QUOTA_EXCEEDED = 4, UNSUPPORTED = 5, LOCKED = 6,
            STORAGE_UNAVAILABLE = 7, BAD_REQUEST = 8, BUSY = 9;
    public static final int READ = 1, WRITE = 2, DELETE = 4, OBSERVE = 8,
            PUBLISH = 16, SUBSCRIBE = 32;
    public static final int CREATE_OP = 1, UPDATE_OP = 2, DELETE_OP = 3;
    public static final int RESOURCE_DATA = 1, RESOURCE_EVENT = 2;
    public static final long FEATURE_MEMORY = 1L, FEATURE_FILE = 1L << 1,
            FEATURE_DATABASE = 1L << 2, FEATURE_CAS = 1L << 3,
            FEATURE_OBSERVE = 1L << 4, FEATURE_EVENT = 1L << 5,
            FEATURE_ACL = 1L << 6, FEATURE_DE_CE = 1L << 7;
    public static final long ALL_FEATURES = FEATURE_MEMORY | FEATURE_FILE | FEATURE_DATABASE
            | FEATURE_CAS | FEATURE_OBSERVE | FEATURE_EVENT | FEATURE_ACL | FEATURE_DE_CE;

    public static final String STATUS = "status", STORE = "store", SCOPE = "scope",
            OWNER = "owner", NAMESPACE = "namespace", KEY = "key", TOPIC = "topic",
            RESOURCE = "resource", SCHEMA_ID = "schemaId", SCHEMA_VERSION = "schemaVersion",
            ENCODING = "encoding", PAYLOAD = "payload", VALUE = "value", PRESENT = "present",
            REVISION = "revision", EPOCH = "epoch", EXPECTED_EPOCH = "expectedEpoch",
            OPERATION = "operation", RIGHTS = "rights",
            MAJOR = "major", MINOR = "minor", MAX_BYTES = "maxBytes", FEATURES = "features";
}
