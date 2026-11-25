package com.android.server.globaldata;

import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.Signature;
import android.os.Binder;
import android.os.Bundle;
import android.os.Environment;
import android.os.Process;
import android.os.UserHandle;
import android.os.UserManager;
import android.util.AtomicFile;
import com.oem.globaldata.ipc.Wire;
import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
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
import java.util.function.Supplier;
import java.util.zip.CRC32;
import java.util.zip.CheckedInputStream;
import java.util.zip.CheckedOutputStream;

/**
 * 完整 DataKey 授权与事件 topic 的服务端权限控制器。每次访问用 Binder UID 核对包名、
 * 当前签名、作用域与 ACL；owner 身份和 grant 都绑定签名摘要，不仅依赖可复用的 UID。
 * ACL 保存在 system DE 目录；加载失败时阻止访问并保留原始文件。
 * 这样包名被不同签名应用重装或 UID 被其他包复用时，旧授权不会落到新身份上；
 * 同包名且签名未变的重装仍可继续匹配原授权。
 */
final class AccessController {
    /** 日志中的类名；签名摘要和业务值不会进入日志。 */
    private static final String TAG = "AccessController";

    /** ACL 二进制文件的魔数和格式版本；与 Wire 协议版本相互独立。 */
    private static final int MAGIC = 0x47444132, VERSION = 1;
    /** 生产环境由系统 Context 查询身份，平台测试可注入受控身份。 */
    interface Identities {
        boolean platformSigned(int uid);
        String signature(String packageName, int userId);
        String signatureInAnyUser(String packageName);
        String[] packagesForUid(int uid);
        Integer installedAppFlags(String packageName, int uid, int userId);
        boolean userUnlocked(int userId);
    }

    static final int DATA = 1, EVENT = 2;
    private static final int MAX_OWNERS = 100000, MAX_GRANTS = 100000;
    private static final int MAX_AUDIENCE_GRANTS = 100000;
    private final Identities identities;
    /**
     * 使用 AtomicFile 保存 ACL，避免掉电或进程退出留下半写入的授权文件；
     * 放在 system DE 目录，使服务可在用户 CE 尚未解锁时恢复授权记录。
     */
    private final AtomicFile file;
    /** 资源 ID -> owner 当前签名摘要；阻止包名被不同签名应用接管。 */
    private final Map<String, String> owners = new HashMap<>();
    /** 数据规则额外保存 Store 和 Key，事件规则的完整 topic 已含在 ownerId 中。 */
    private record GrantKey(String ownerId, int store, String key, String target) {}
    private final Map<GrantKey, Grant> grants = new HashMap<>();
    // 完整地址包含 Store，避免同名 Key 在三种后端之间共享范围权限。
    private record AudienceKey(StoreAddress address, int audience) {}
    private final Map<AudienceKey, Integer> audienceGrants = new HashMap<>();
    // 无法验证历史 owner 时，当前包名和签名不能作为访问旧数据的依据。
    private boolean unavailable;
    /** 单个被授权包的签名快照及其权限位。 */
    private static final class Grant {
        /** signature 为目标包签名摘要；rights 是 Wire.READ 等位掩码。 */
        final String signature;
        final int rights;
        Grant(String signature, int rights) { this.signature = signature; this.rights = rights; }
    }
    /** 签名绑定地址。数据按 scope/user/owner 绑定，事件按完整 topic 绑定。 */
    static final class Resource {
        /** type 区分数据/事件；scope/userId 区分设备或 Android 用户。 */
        final int type, scope, userId;
        /** owner 是资源所属包名，事件的 name 是完整 topic。 */
        final String owner, name;
        Resource(int type, int scope, int userId, String owner, String name) {
            // 设备级 DE 数据属于固定 user 0；用户级地址使用真实调用者所属 user。
            this.type = type; this.scope = scope; this.userId = scope == Wire.DEVICE_DE ? 0 : userId;
            this.owner = owner; this.name = name;
        }
        /** 持久化的 owner 签名绑定 ID。 */
        String id() { return type + "|" + scope + "|" + userId + "|" + owner + "|" + name; }
    }
    AccessController(Context context) {
        this(new File(Environment.getDataSystemDeDirectory(0), "globaldata/v2"),
                new AndroidIdentities(context));
    }

    AccessController(File directory, Identities identities) {
        this.identities = identities;
        file = new AtomicFile(new File(directory, "acl.bin"));
        try {
            StorageFiles.prepareDirectory(directory);
            load();
        } catch (IOException | RuntimeException e) {
            TLog.e(TAG, "ACL unavailable type=" + e.getClass().getSimpleName());
            owners.clear();
            grants.clear();
            audienceGrants.clear();
            unavailable = true;
        }
    }

    /** 数据 owner 签名在同一 Scope 和 Android 用户的各 Store 间共用。 */
    static Resource data(StoreAddress key) {
        return new Resource(DATA, key.scope, key.userId, key.owner, "");
    }
    /** 解析事件 scope/owner/topic，并把 userId 绑定到真实调用方。 */
    static Resource event(Bundle b, int callerUserId) {
        if (b == null) throw new IllegalArgumentException("missing event address");
        int scope = b.getInt(Wire.SCOPE);
        String owner = b.getString(Wire.OWNER), topic = b.getString(Wire.TOPIC);
        if (scope < Wire.DEVICE_DE || scope > Wire.USER_CE || !StoreAddress.name(owner, 180)
                || !StoreAddress.name(topic, 180)) throw new IllegalArgumentException("event address");
        return new Resource(EVENT, scope, callerUserId, owner, topic);
    }
    private static boolean validPrefix(Resource resource) {
        return resource.type == DATA ? resource.name.isEmpty()
                : resource.name.startsWith("sys.")
                    || resource.name.startsWith("events." + resource.owner + "/");
    }

    private static final class AndroidIdentities implements Identities {
        private final Context context;

        AndroidIdentities(Context context) {
            this.context = context;
        }

        private static <T> T withSystemIdentity(Supplier<T> operation) {
            // 使用服务身份查询包可见性，权限判断仍使用入口捕获的真实调用 UID。
            long token = Binder.clearCallingIdentity();
            try {
                return operation.get();
            } finally {
                Binder.restoreCallingIdentity(token);
            }
        }

        private PackageManager packages(int userId) {
            return context.createContextAsUser(UserHandle.of(userId), 0).getPackageManager();
        }

        @Override public String[] packagesForUid(int uid) {
            return withSystemIdentity(() -> context.getPackageManager().getPackagesForUid(uid));
        }

        @Override public Integer installedAppFlags(String packageName, int uid, int userId) {
            return withSystemIdentity(() -> {
                try {
                    ApplicationInfo info = packages(userId).getApplicationInfo(packageName, 0);
                    if (info.uid == uid && (info.flags & ApplicationInfo.FLAG_INSTALLED) != 0) {
                        return info.flags;
                    }
                } catch (PackageManager.NameNotFoundException e) {
                    // UID 映射查询后 App 仍可能被卸载。
                }
                return null;
            });
        }

        @Override public boolean userUnlocked(int userId) {
            return withSystemIdentity(() -> {
                UserManager users = context.getSystemService(UserManager.class);
                return users != null && users.isUserUnlocked(UserHandle.of(userId));
            });
        }

        @Override public boolean platformSigned(int uid) {
            return uid == Process.SYSTEM_UID || withSystemIdentity(() ->
                    context.getPackageManager().checkSignatures(uid, Process.SYSTEM_UID)
                            == PackageManager.SIGNATURE_MATCH);
        }
        /**
         * 对指定用户安装的包计算当前 APK 签名证书 SHA-256 集合摘要。
         * 多签名先排序再拼接，证书不可读取时返回 null，后续权限检查按拒绝处理。
         */
        @Override public String signature(String packageName, int userId) {
            return withSystemIdentity(() -> readSignature(packageName, userId));
        }

        @Override public String signatureInAnyUser(String packageName) {
            return withSystemIdentity(() -> {
                UserManager users = context.getSystemService(UserManager.class);
                if (users == null) return null;
                for (UserHandle user : users.getUserHandles(true)) {
                    String digest = readSignature(packageName, user.getIdentifier());
                    if (digest != null) return digest;
                }
                return null;
            });
        }

        private String readSignature(String packageName, int userId) {
            try {
                PackageInfo info = packages(userId).getPackageInfo(packageName,
                        PackageManager.GET_SIGNING_CERTIFICATES);
                if (info.applicationInfo == null
                        || (info.applicationInfo.flags & ApplicationInfo.FLAG_INSTALLED) == 0) return null;
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
                // 多签名数组的返回顺序不应改变同一包的身份摘要。
                Arrays.sort(digests); return String.join(":", digests);
            } catch (Exception e) { return null; }
        }
    }

    /** 核对包名确实属于 Binder 调用 UID，防止请求方自报任意 owner。 */
    private boolean uidOwns(int uid, String packageName) {
        String[] packages = identities.packagesForUid(uid);
        if (packages == null) return false;
        for (String candidate : packages) if (packageName.equals(candidate)) return true;
        return false;
    }
    /**
     * 在查询 owner/grant 之前先排除非法命名和受保护作用域；
     */
    private boolean available(Resource r, int uid) {
        if (!validPrefix(r)) return false;
        if ((r.scope == Wire.DEVICE_DE || r.name.startsWith("sys."))
                && !identities.platformSigned(uid)) return false;
        return true;
    }
    /**
     * 检查已有 owner 或 grant 是否覆盖 required 权限位。每次重新计算当前包签名，
     * 是为了让换签名重装的包立即失去旧 owner/grant 身份；仅匹配 UID 不足以做到这一点。
     * ACL 无法恢复时返回 STORAGE_UNAVAILABLE；正常情况下 CE 锁定返回 LOCKED。
     */
    synchronized int check(int uid, Resource r, int required) {
        if (unavailable) return Wire.STORAGE_UNAVAILABLE;
        TLog.d(TAG, "check uid=" + uid + " " + TLog.address(r) + " required=" + required);
        // 1. CE 锁定单独返回 LOCKED，调用方可在用户解锁后重试。
        if (r.scope == Wire.USER_CE && !identities.userUnlocked(r.userId)) return Wire.LOCKED;
        // 2. 在查看持久化 grant 前排除非法地址和设备/系统专属资源。
        if (!available(r, uid)) {
            TLog.w(TAG, "unavailable uid=" + uid + " scope=" + r.scope);
            return Wire.PERMISSION_DENIED;
        }
        String[] packages = identities.packagesForUid(uid);
        if (ownerSignature(r, uid, packages) != null) return Wire.OK;
        int granted = directRights(r, null, uid, r.userId, packages);
        return (granted & required) == required ? Wire.OK : Wire.PERMISSION_DENIED;
    }

    private String ownerSignature(Resource r, int uid, String[] packages) {
        if (packages == null || !Arrays.asList(packages).contains(r.owner)) return null;
        if (r.type == DATA && identities.installedAppFlags(
                r.owner, uid, UserHandle.getUserId(uid)) == null) return null;
        String current = identities.signature(r.owner, r.userId);
        String original = owners.get(r.id());
        return original == null || original.equals(current) ? current : null;
    }

    private static GrantKey grantKey(Resource r, StoreAddress key, String target) {
        return new GrantKey(r.id(), key == null ? 0 : key.store,
                key == null ? "" : key.key, target);
    }

    private int directRights(Resource r, StoreAddress key, int uid, int user, String[] packages) {
        if (packages == null) return 0;
        int rights = 0;
        for (String pkg : packages) {
            Grant rule = grants.get(grantKey(r, key, pkg));
            if (rule == null) continue;
            if (key != null && identities.installedAppFlags(pkg, uid, user) == null) continue;
            if (rule.signature.equals(identities.signature(pkg, user))) rights |= rule.rights;
        }
        return rights;
    }

    private static boolean validAudience(Resource r, int audience) {
        if (r.type != DATA) return false;
        if (audience == Wire.AUDIENCE_DEVICE_ALL) return r.scope == Wire.DEVICE_DE;
        return (r.scope == Wire.USER_DE || r.scope == Wire.USER_CE)
                && (audience == Wire.AUDIENCE_USER_ALL
                    || audience == Wire.AUDIENCE_USER_PREINSTALLED
                    || audience == Wire.AUDIENCE_USER_SAME_SIGNATURE);
    }

    private int audienceRights(StoreAddress key, Resource r, int uid, int user, String[] packages) {
        if (packages == null || packages.length == 0) return 0;
        boolean device = key.scope == Wire.DEVICE_DE;
        int allAudience = device ? Wire.AUDIENCE_DEVICE_ALL : Wire.AUDIENCE_USER_ALL;
        int all = audienceGrants.getOrDefault(new AudienceKey(key, allAudience), 0);
        int preinstalled = device ? 0 : audienceGrants.getOrDefault(
                new AudienceKey(key, Wire.AUDIENCE_USER_PREINSTALLED), 0);
        int sameSignature = device ? 0 : audienceGrants.getOrDefault(
                new AudienceKey(key, Wire.AUDIENCE_USER_SAME_SIGNATURE), 0);
        if ((all | preinstalled | sameSignature) == 0) return 0;

        // 设备级始终核对 User 0 的 owner，消费者安装状态则按自己的用户确认。
        String original = owners.get(r.id());
        int granted = 0;
        for (String pkg : packages) {
            Integer flags = identities.installedAppFlags(pkg, uid, user);
            if (flags == null) continue;
            granted |= all;
            if ((flags & (ApplicationInfo.FLAG_SYSTEM | ApplicationInfo.FLAG_UPDATED_SYSTEM_APP))
                    != 0) granted |= preinstalled;
            if (sameSignature != 0 && original.equals(identities.signature(pkg, user))) {
                granted |= sameSignature;
            }
            if (granted == (all | preinstalled | sameSignature)) break;
        }
        return granted;
    }

    /** 数据操作和通知复核共用完整 Key 鉴权。 */
    synchronized int authorizeData(int uid, StoreAddress key, int required, boolean createOwner) {
        if (unavailable) return Wire.STORAGE_UNAVAILABLE;
        int user = UserHandle.getUserId(uid);
        Resource r = data(key);
        if (key.userId != (key.scope == Wire.DEVICE_DE ? 0 : user)) return Wire.PERMISSION_DENIED;
        if (r.scope == Wire.USER_CE && !identities.userUnlocked(user)) return Wire.LOCKED;

        String[] packages = identities.packagesForUid(uid);
        if (available(r, uid) && ownerSignature(r, uid, packages) != null) {
            return createOwner ? ensureOwner(uid, r) : Wire.OK;
        }

        // 普通 App 只能用该完整 Key 的显式规则访问 DEVICE_DE。
        // 每次访问和通知投递重新核对 owner 签名，防止历史规则随包名转移。
        String original = owners.get(r.id());
        if (original == null || !original.equals(identities.signature(r.owner, r.userId))) {
            return Wire.PERMISSION_DENIED;
        }
        int granted = directRights(r, key, uid, user, packages);
        if ((granted & required) == required) return Wire.OK;
        granted |= audienceRights(key, r, uid, user, packages);
        return (granted & required) == required ? Wire.OK : Wire.PERMISSION_DENIED;
    }

    /** 单 Key 范围授权仅由真实 owner 管理，并与首次 owner 绑定一起提交。 */
    synchronized int changeDataAudience(
            int uid, StoreAddress key, int audience, int rights, boolean grant) {
        if (unavailable) return Wire.STORAGE_UNAVAILABLE;
        Resource r = data(key);
        int user = UserHandle.getUserId(uid);
        if (!validAudience(r, audience) || !validRights(DATA, rights)) {
            return Wire.BAD_REQUEST;
        }
        if (key.userId != (key.scope == Wire.DEVICE_DE ? 0 : user)) return Wire.PERMISSION_DENIED;
        if (r.scope == Wire.USER_CE && !identities.userUnlocked(user)) return Wire.LOCKED;
        if (!available(r, uid)) return Wire.PERMISSION_DENIED;
        String digest = ownerSignature(r, uid, identities.packagesForUid(uid));
        if (digest == null) return Wire.PERMISSION_DENIED;

        String ownerId = r.id();
        String priorOwner = owners.get(ownerId);
        AudienceKey rule = new AudienceKey(key, audience);
        int prior = audienceGrants.getOrDefault(rule, 0);
        int next = grant ? prior | rights : prior & ~rights;
        if (next == prior) return Wire.OK;
        if ((priorOwner == null && owners.size() >= MAX_OWNERS)
                || (prior == 0 && audienceGrants.size() >= MAX_AUDIENCE_GRANTS)) {
            return Wire.QUOTA_EXCEEDED;
        }

        if (priorOwner == null) owners.put(ownerId, digest);
        setAudienceRights(rule, next);
        try {
            persist();
            return Wire.OK;
        } catch (RuntimeException e) {
            // 同时回滚首次登记和规则，避免失败的授权在本次进程内生效。
            if (priorOwner == null) owners.remove(ownerId);
            setAudienceRights(rule, prior);
            return Wire.STORAGE_UNAVAILABLE;
        }
    }

    private void setAudienceRights(AudienceKey rule, int rights) {
        if (rights == 0) audienceGrants.remove(rule);
        else audienceGrants.put(rule, rights);
    }

    /**
     * 首次写入或变更授权时固定 owner 签名，使旧数据和授权长期属于最初签名者，
     * 而不是后来占用同一包名的应用。文件只读或持久化失败时撤回新内存记录，
     * 避免本次进程看似授权成功、重启后却丢失 owner 身份。
     */
    private int ensureOwner(int uid, Resource r) {
        TLog.d(TAG, "ensureOwner uid=" + uid + " " + TLog.address(r));
        // 1. 先检查用户解锁、受保护作用域和 UID 真实持有的 owner 包名。
        if (r.scope == Wire.USER_CE && !identities.userUnlocked(r.userId)) return Wire.LOCKED;
        if (!available(r, uid) || !uidOwns(uid, r.owner)) {
            TLog.w(TAG, "owner check denied uid=" + uid + " reason=identity_or_scope");
            return Wire.PERMISSION_DENIED;
        }
        // 2. 对比持久化的首次签名；同包名换签名后不能接管旧资源。
        String digest = identities.signature(r.owner, r.userId);
        if (digest == null) {
            TLog.w(TAG, "owner check denied uid=" + uid + " reason=signature_unavailable");
            return Wire.PERMISSION_DENIED;
        }
        String prior = owners.get(r.id());
        if (prior != null && !prior.equals(digest)) {
            TLog.w(TAG, "owner check denied uid=" + uid + " reason=signature_changed");
            return Wire.PERMISSION_DENIED;
        }
        // 3. 仅首次登记才写文件；写失败时撤销内存变化，防止本次进程误认为已登记。
        if (prior == null) {
            if (owners.size() >= MAX_OWNERS) return Wire.QUOTA_EXCEEDED;
            owners.put(r.id(), digest);
            try {
                persist();
                TLog.d(TAG, "owner persisted uid=" + uid + " " + TLog.address(r));
            } catch (RuntimeException e) {
                TLog.e(TAG, "owner persist failed type=" + e.getClass().getSimpleName());
                owners.remove(r.id()); return Wire.STORAGE_UNAVAILABLE;
            }
        }
        return Wire.OK;
    }
    /**
     * 写入和发布时按需登记真实 owner；共享写入者即使持有 WRITE/PUBLISH grant，
     * 也不能借此创建或接管 owner 身份记录，必须继续依靠授予的权限位。
     */
    synchronized int authorize(int uid, Resource r, int right, boolean createOwner) {
        if (unavailable) return Wire.STORAGE_UNAVAILABLE;
        TLog.d(TAG, "authorize uid=" + uid + " right=" + right
                + " createOwner=" + createOwner + " " + TLog.address(r));
        // 共享写入者可使用 owner 的 grant，但只有真实 owner 能建立身份记录。
        if (createOwner && uidOwns(uid, r.owner)) {
            int status = ensureOwner(uid, r); if (status != Wire.OK) return status;
        }
        return check(uid, r, right);
    }
    synchronized int changeDataGrant(int uid, StoreAddress key, String target, int rights,
                                     boolean grant) {
        return changeGrant(uid, data(key), key, target, rights, grant);
    }

    synchronized int changeEventGrant(int uid, Resource topic, String target, int rights,
                                      boolean grant) {
        return changeGrant(uid, topic, null, target, rights, grant);
    }

    private int changeGrant(int uid, Resource r, StoreAddress key, String target, int rights,
                            boolean grant) {
        if (unavailable) return Wire.STORAGE_UNAVAILABLE;
        if (!validRights(r.type, rights) || !StoreAddress.name(target, 180)) {
            return Wire.BAD_REQUEST;
        }
        int user = UserHandle.getUserId(uid);
        if (r.scope != Wire.DEVICE_DE && r.userId != user) return Wire.PERMISSION_DENIED;
        if (r.scope == Wire.USER_CE && !identities.userUnlocked(user)) return Wire.LOCKED;
        if (!available(r, uid)) return Wire.PERMISSION_DENIED;
        String ownerDigest = ownerSignature(r, uid, identities.packagesForUid(uid));
        if (ownerDigest == null) return Wire.PERMISSION_DENIED;
        String ownerId = r.id();
        String priorOwner = owners.get(ownerId);

        GrantKey id = grantKey(r, key, target);
        Grant prior = grants.get(id);
        String digest;
        int next;
        if (grant) {
            // DEVICE_DE 数据共用一个地址，目标包也可能只安装在次用户。
            digest = key != null && r.scope == Wire.DEVICE_DE
                    ? identities.signatureInAnyUser(target) : identities.signature(target, r.userId);
            if (digest == null) return Wire.NOT_FOUND;
            next = rights | (prior != null && digest.equals(prior.signature) ? prior.rights : 0);
            if (prior != null && next == prior.rights && digest.equals(prior.signature)) {
                return Wire.OK;
            }
        } else {
            if (prior == null || (prior.rights & rights) == 0) return Wire.OK;
            digest = prior.signature;
            next = prior.rights & ~rights;
        }
        if ((prior == null && grants.size() >= MAX_GRANTS)
                || (priorOwner == null && owners.size() >= MAX_OWNERS)) return Wire.QUOTA_EXCEEDED;

        // 首次签名绑定与授权只提交一次，失败时两者一起回滚。
        if (priorOwner == null) owners.put(ownerId, ownerDigest);
        if (next == 0) grants.remove(id);
        else grants.put(id, new Grant(digest, next));
        try {
            persist();
            return Wire.OK;
        } catch (RuntimeException e) {
            if (priorOwner == null) owners.remove(ownerId);
            if (prior == null) grants.remove(id);
            else grants.put(id, prior);
            return Wire.STORAGE_UNAVAILABLE;
        }
    }

    private static int allowedRights(int type) {
        return type == DATA ? Wire.READ | Wire.WRITE | Wire.DELETE | Wire.OBSERVE
                : Wire.PUBLISH | Wire.SUBSCRIBE;
    }

    private static boolean validRights(int type, int rights) {
        return rights != 0 && (rights & ~allowedRights(type)) == 0;
    }

    private static int resourceType(String id) {
        if (id.startsWith(DATA + "|")) return DATA;
        if (id.startsWith(EVENT + "|")) return EVENT;
        return 0;
    }

    private static Resource parseOwnerId(String id) {
        // 持久化地址必须能由正常请求生成，否则损坏的 owner 记录可能静默失效。
        if (id.length() > 380) return null;
        String[] parts = id.split("\\|", -1);
        int type = resourceType(id);
        if (type == 0 || parts.length != 5 || !StoreAddress.name(parts[3], 180)
                || (type == DATA ? !parts[4].isEmpty()
                        : !StoreAddress.name(parts[4], 180))) return null;
        try {
            int scope = Integer.parseInt(parts[1]);
            int user = Integer.parseInt(parts[2]);
            if (scope < Wire.DEVICE_DE || scope > Wire.USER_CE || user < 0) return null;
            Resource resource = new Resource(type, scope, user, parts[3], parts[4]);
            return resource.id().equals(id) && validPrefix(resource) ? resource : null;
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static boolean validDigest(String digest) {
        int length = digest.length();
        if (length == 0 || length % 65 != 64) return false;
        for (int i = 0; i < length; i++) {
            char c = digest.charAt(i);
            if (i % 65 == 64 ? c != ':' : !(c >= '0' && c <= '9' || c >= 'a' && c <= 'f')) {
                return false;
            }
        }
        return true;
    }

    private void load() throws IOException {
        File committed = StorageFiles.committedSource(file);
        if (committed == null) {
            // 只有临时文件时无法验证历史 ACL，不能当作全新安装放行。
            if (StorageFiles.exists(StorageFiles.sibling(file, ".new"))) {
                throw new IOException("ACL has no committed snapshot");
            }
            return;
        }
        CRC32 crc = new CRC32();
        try (DataInputStream in = new DataInputStream(new CheckedInputStream(
                new BufferedInputStream(new FileInputStream(committed)), crc))) {
            if (in.readInt() != MAGIC) throw new IOException("ACL magic");
            if (in.readInt() != VERSION) throw new IOException("ACL version");
            int count = in.readInt();
            if (count < 0 || count > MAX_OWNERS) throw new IOException("ACL owner count");
            for (int i = 0; i < count; i++) {
                String id = in.readUTF(), digest = in.readUTF();
                if (parseOwnerId(id) == null || !validDigest(digest)
                        || owners.putIfAbsent(id, digest) != null) {
                    throw new IOException("invalid or duplicate ACL owner");
                }
            }

            count = in.readInt();
            if (count < 0 || count > MAX_GRANTS) throw new IOException("ACL grant count");
            for (int i = 0; i < count; i++) {
                String ownerId = in.readUTF();
                int store = in.readInt();
                String key = in.readUTF(), target = in.readUTF(), digest = in.readUTF();
                int rights = in.readInt();
                int type = resourceType(ownerId);
                boolean validKey = type == DATA
                        ? store >= Wire.MEMORY && store <= Wire.DATABASE && StoreAddress.name(key, 180)
                        : store == 0 && key.isEmpty();
                if (!owners.containsKey(ownerId) || !validKey
                        || !StoreAddress.name(target, 180) || !validDigest(digest)
                        || !validRights(type, rights)
                        || grants.putIfAbsent(new GrantKey(ownerId, store, key, target),
                                new Grant(digest, rights)) != null) {
                    throw new IOException("invalid or duplicate ACL grant");
                }
            }
            loadAudienceGrants(in);
            long expected = crc.getValue(); // 校验和只覆盖快照正文，不包括尾部自身。
            if (in.readLong() != expected) throw new IOException("ACL checksum mismatch");
            if (in.read() != -1) throw new IOException("trailing ACL data");
        }
    }
    private void loadAudienceGrants(DataInputStream in) throws IOException {
        int count = in.readInt();
        if (count < 0 || count > MAX_AUDIENCE_GRANTS) throw new IOException("ACL audience count");
        for (int i = 0; i < count; i++) {
            String ownerId = in.readUTF();
            int store = in.readInt();
            String key = in.readUTF();
            int audience = in.readInt(), rights = in.readInt();
            Resource r = owners.containsKey(ownerId) ? parseOwnerId(ownerId) : null;
            if (r == null || !validAudience(r, audience) || !validRights(DATA, rights)
                    || store < Wire.MEMORY || store > Wire.DATABASE || !StoreAddress.name(key, 180)) {
                throw new IOException("invalid ACL audience grant");
            }
            StoreAddress address = new StoreAddress(store, r.scope, r.userId, r.owner, key);
            if (audienceGrants.putIfAbsent(new AudienceKey(address, audience), rights) != null) {
                throw new IOException("duplicate ACL audience grant");
            }
        }
    }

    /**
     * 将 owner 与 grant 作为同一个快照原子写入，使两类身份记录不会只更新一半；
     * 写入失败由 AtomicFile 回滚临时文件，上层再恢复对应内存映射。
     */
    private void persist() {
        FileOutputStream raw = null;
        CRC32 crc = new CRC32();
        try {
            // startWrite/finishWrite 使 owner 与 grant 作为一个完整快照替换旧文件。
            raw = file.startWrite();
            DataOutputStream out = new DataOutputStream(new CheckedOutputStream(
                    new BufferedOutputStream(raw), crc));
            out.writeInt(MAGIC); out.writeInt(VERSION); out.writeInt(owners.size());
            for (Map.Entry<String, String> e : owners.entrySet()) { out.writeUTF(e.getKey()); out.writeUTF(e.getValue()); }
            out.writeInt(grants.size());
            for (Map.Entry<GrantKey, Grant> e : grants.entrySet()) {
                GrantKey id = e.getKey();
                out.writeUTF(id.ownerId()); out.writeInt(id.store());
                out.writeUTF(id.key()); out.writeUTF(id.target());
                out.writeUTF(e.getValue().signature); out.writeInt(e.getValue().rights);
            }
            out.writeInt(audienceGrants.size());
            for (Map.Entry<AudienceKey, Integer> entry : audienceGrants.entrySet()) {
                AudienceKey rule = entry.getKey();
                StoreAddress address = rule.address();
                out.writeUTF(data(address).id());
                out.writeInt(address.store);
                out.writeUTF(address.key);
                out.writeInt(rule.audience());
                out.writeInt(entry.getValue());
            }
            out.writeLong(crc.getValue());
            out.flush();
            StorageFiles.finishWrite(file, raw);
        } catch (IOException e) {
            TLog.e(TAG, "commit failed type=" + e.getClass().getSimpleName());
            // AtomicFile 恢复旧文件；上层再撤销本次内存映射修改。
            if (raw != null) file.failWrite(raw);
            throw new IllegalStateException("ACL commit failed", e);
        }
    }
}
