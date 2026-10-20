package com.android.server.globaldata;

/** Only the address fields required to compile the real service TLog in host tests. */
final class AccessController {
    static final class Resource {
        int type, scope, userId;
        String owner, name;
    }
}
