package com.android.server.globaldata;

import android.content.Context;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.Signature;
import android.os.Bundle;
import android.os.Environment;
import android.os.Process;
import android.os.SELinux;
import android.os.UserHandle;
import android.os.UserManager;
import android.util.AtomicFile;
import com.oem.globaldata.ipc.Wire;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;

/** Owner and grants are bound to package plus current signing certificates, never bare UID. */
final class AccessController {
    private static final int MAGIC = 0x47444131, VERSION = 1;
    private final Context context;
    private final AtomicFile file;
    private final Map<String, String> owners = new HashMap<>();
    private final Map<String, Grant> grants = new HashMap<>();
    private boolean readOnly;
    private static final class Grant {
        final String signature;
        final int rights;
        Grant(String signature, int rights) { this.signature = signature; this.rights = rights; }
    }
    static final class Resource {
        final int type, scope, userId;
        final String owner, name;
        Resource(int type, int scope, int userId, String owner, String name) {
            this.type = type; this.scope = scope; this.userId = scope == Wire.DEVICE_DE ? 0 : userId;
            this.owner = owner; this.name = name;
        }
        String id() { return type + "|" + scope + "|" + userId + "|" + owner + "|" + name; }
    }
    AccessController(Context context) {
        this.context = context;
        File dir = new File(Environment.getDataSystemDeDirectory(0), "globaldata");
        if (!dir.exists()) {
            if (!dir.mkdirs() || !SELinux.restoreconRecursive(dir))
                throw new IllegalStateException("ACL directory");
        }
        file = new AtomicFile(new File(dir, "acl-v1.bin"));
        load();
    }
    static Resource data(StoreAddress key) {
        return new Resource(Wire.RESOURCE_DATA, key.scope, key.userId, key.owner, key.namespace);
    }
    static Resource event(Bundle b, int callerUserId) {
        if (b == null) throw new IllegalArgumentException("missing event address");
        int scope = b.getInt(Wire.SCOPE);
        String owner = b.getString(Wire.OWNER), topic = b.getString(Wire.TOPIC);
        if (scope < Wire.DEVICE_DE || scope > Wire.USER_CE || !StoreAddress.name(owner, 180)
                || !StoreAddress.name(topic, 180)) throw new IllegalArgumentException("event address");
        return new Resource(Wire.RESOURCE_EVENT, scope, callerUserId, owner, topic);
    }
    static Resource grantResource(Bundle b, int callerUserId) {
        if (b == null) throw new IllegalArgumentException("missing resource");
        int type = b.getInt(Wire.RESOURCE), scope = b.getInt(Wire.SCOPE);
        String owner = b.getString(Wire.OWNER);
        String name = b.getString(type == Wire.RESOURCE_DATA ? Wire.NAMESPACE : Wire.TOPIC);
        if ((type != Wire.RESOURCE_DATA && type != Wire.RESOURCE_EVENT)
                || scope < Wire.DEVICE_DE || scope > Wire.USER_CE
                || !StoreAddress.name(owner, 180) || !StoreAddress.name(name, 180))
            throw new IllegalArgumentException("invalid resource");
        return new Resource(type, scope, callerUserId, owner, name);
    }
    private boolean validPrefix(Resource resource) {
        if (resource.name.startsWith("sys.")) return true;
        if (resource.type == Wire.RESOURCE_DATA)
            return resource.name.equals("share." + resource.owner)
                    || resource.name.equals("app." + resource.owner);
        return resource.name.startsWith("events." + resource.owner + "/");
    }
    private boolean platformSigned(int uid) {
        return uid == Process.SYSTEM_UID || context.getPackageManager().checkSignatures(uid,
                Process.SYSTEM_UID) == PackageManager.SIGNATURE_MATCH;
    }
    private String signature(String packageName, int userId) {
        try {
            Context userContext = context.createContextAsUser(UserHandle.of(userId), 0);
            PackageInfo info = userContext.getPackageManager().getPackageInfo(packageName,
                    PackageManager.GET_SIGNING_CERTIFICATES);
            if (info.signingInfo == null) return null;
            Signature[] signers = info.signingInfo.getApkContentsSigners();
            if (signers == null || signers.length == 0) return null;
            String[] digests = new String[signers.length];
            for (int i = 0; i < signers.length; i++) {
                byte[] hash = MessageDigest.getInstance("SHA-256").digest(signers[i].toByteArray());
                StringBuilder hex = new StringBuilder();
                for (byte value : hash) hex.append(String.format("%02x", value & 0xff));
                digests[i] = hex.toString();
            }
            Arrays.sort(digests); return String.join(":", digests);
        } catch (Exception e) { return null; }
    }
    private boolean uidOwns(int uid, String packageName) {
        String[] packages = context.getPackageManager().getPackagesForUid(uid);
        if (packages == null) return false;
        for (String candidate : packages) if (packageName.equals(candidate)) return true;
        return false;
    }
    private boolean available(Resource r, int uid) {
        if (!validPrefix(r)) return false;
        if (r.scope == Wire.DEVICE_DE && !platformSigned(uid)) return false;
        if (r.name.startsWith("sys.") && !platformSigned(uid)) return false;
        if (r.scope == Wire.USER_CE) {
            UserManager users = context.getSystemService(UserManager.class);
            if (users == null || !users.isUserUnlocked(UserHandle.of(r.userId))) return false;
        }
        return true;
    }
    synchronized int check(int uid, Resource r, int required) {
        if (r.scope == Wire.USER_CE) {
            UserManager users = context.getSystemService(UserManager.class);
            if (users == null || !users.isUserUnlocked(UserHandle.of(r.userId))) return Wire.LOCKED;
        }
        if (!available(r, uid)) return Wire.PERMISSION_DENIED;
        String ownerDigest = owners.get(r.id());
        if (uidOwns(uid, r.owner)) {
            String current = signature(r.owner, r.userId);
            if (current != null && (ownerDigest == null || current.equals(ownerDigest))) return Wire.OK;
        }
        String[] packages = context.getPackageManager().getPackagesForUid(uid);
        if (packages != null) for (String pkg : packages) {
            Grant grant = grants.get(r.id() + "|" + pkg);
            if (grant != null && (grant.rights & required) == required
                    && grant.signature.equals(signature(pkg, r.userId))) return Wire.OK;
        }
        return Wire.PERMISSION_DENIED;
    }
    int ensureOwner(int uid, Resource r) {
        if (r.scope == Wire.USER_CE) {
            UserManager users = context.getSystemService(UserManager.class);
            if (users == null || !users.isUserUnlocked(UserHandle.of(r.userId)))
                return Wire.LOCKED;
        }
        if (!available(r, uid) || !uidOwns(uid, r.owner)) return Wire.PERMISSION_DENIED;
        String digest = signature(r.owner, r.userId);
        if (digest == null) return Wire.PERMISSION_DENIED;
        String prior = owners.get(r.id());
        if (prior != null && !prior.equals(digest)) return Wire.PERMISSION_DENIED;
        if (prior == null) {
            if (readOnly) return Wire.STORAGE_UNAVAILABLE;
            owners.put(r.id(), digest);
            try { persist(); } catch (RuntimeException e) { owners.remove(r.id()); return Wire.STORAGE_UNAVAILABLE; }
        }
        return Wire.OK;
    }
    synchronized int authorize(int uid, Resource r, int right, boolean createOwner) {
        // Shared writers may use an owner's grant; only the actual owner creates its identity record.
        if (createOwner && uidOwns(uid, r.owner)) {
            int status = ensureOwner(uid, r); if (status != Wire.OK) return status;
        }
        return check(uid, r, right);
    }
    synchronized int changeGrant(int uid, Resource r, String target, int rights, boolean grant) {
        int allowed = r.type == Wire.RESOURCE_DATA
                ? Wire.READ | Wire.WRITE | Wire.DELETE | Wire.OBSERVE
                : Wire.PUBLISH | Wire.SUBSCRIBE;
        if (rights == 0 || (rights & ~allowed) != 0 || !StoreAddress.name(target, 180))
            return Wire.BAD_REQUEST;
        int owner = ensureOwner(uid, r);
        if (owner != Wire.OK) return owner;
        if (readOnly) return Wire.STORAGE_UNAVAILABLE;
        String id = r.id() + "|" + target; Grant prior = grants.get(id);
        String digest = grant ? signature(target, r.userId) : null;
        if (grant && digest == null) return Wire.NOT_FOUND;
        // A reinstalled package with a different signer never inherits its old grant bits.
        if (grant) grants.put(id, new Grant(digest,
                rights | (prior != null && digest.equals(prior.signature) ? prior.rights : 0)));
        else if (prior != null) {
            int remaining = prior.rights & ~rights;
            if (remaining == 0) grants.remove(id); else grants.put(id, new Grant(prior.signature, remaining));
        }
        try { persist(); return Wire.OK; }
        catch (RuntimeException e) {
            if (prior == null) grants.remove(id); else grants.put(id, prior);
            return Wire.STORAGE_UNAVAILABLE;
        }
    }
    private void load() {
        if (!file.getBaseFile().exists()
                && !new File(file.getBaseFile().getPath() + ".bak").exists()) return;
        try (FileInputStream raw = file.openRead(); DataInputStream in = new DataInputStream(raw)) {
            if (in.readInt() != MAGIC || in.readInt() != VERSION) throw new IOException("ACL format");
            int count = in.readInt(); if (count < 0 || count > 100000) throw new IOException("ACL count");
            for (int i = 0; i < count; i++) owners.put(in.readUTF(), in.readUTF());
            count = in.readInt(); if (count < 0 || count > 100000) throw new IOException("grant count");
            for (int i = 0; i < count; i++) grants.put(in.readUTF(), new Grant(in.readUTF(), in.readInt()));
        } catch (IOException e) {
            owners.clear(); grants.clear(); readOnly = true; // Fail closed; never overwrite damaged ACL.
        }
    }
    private void persist() {
        FileOutputStream raw = null;
        try {
            raw = file.startWrite(); DataOutputStream out = new DataOutputStream(raw);
            out.writeInt(MAGIC); out.writeInt(VERSION); out.writeInt(owners.size());
            for (Map.Entry<String, String> e : owners.entrySet()) { out.writeUTF(e.getKey()); out.writeUTF(e.getValue()); }
            out.writeInt(grants.size());
            for (Map.Entry<String, Grant> e : grants.entrySet()) {
                out.writeUTF(e.getKey()); out.writeUTF(e.getValue().signature); out.writeInt(e.getValue().rights);
            }
            out.flush(); file.finishWrite(raw);
        } catch (IOException e) {
            if (raw != null) file.failWrite(raw);
            throw new IllegalStateException("ACL commit failed", e);
        }
    }
}
