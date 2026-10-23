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

/**
 * 数据 namespace 与事件 topic 的服务端权限控制器。每次访问用 Binder UID 核对包名、
 * 当前签名、作用域与 ACL；owner 身份和 grant 都绑定签名摘要，不仅依赖可复用的 UID。
 * ACL 保存在 system DE 目录；读取损坏时清空内存记录并禁止后续写入，保留原始文件。
 * 这样包名被不同签名应用重装或 UID 被其他包复用时，旧授权不会落到新身份上；
 * 同包名且签名未变的重装仍可继续匹配原授权。
 */
final class AccessController {
    /** 日志中的类名；签名摘要和业务值不会进入日志。 */
    private static final String TAG = "AccessController";

    /** ACL 二进制文件的魔数和格式版本；与 Wire 协议版本相互独立。 */
    private static final int MAGIC = 0x47444131, VERSION = 1;
    /** 用于查询包、签名和用户解锁状态的系统 Context。 */
    private final Context context;
    /**
     * 使用 AtomicFile 保存 ACL，避免掉电或进程退出留下半写入的授权文件；
     * 放在 system DE 目录，使服务可在用户 CE 尚未解锁时恢复授权记录。
     */
    private final AtomicFile file;
    /** 资源 ID -> owner 当前签名摘要；阻止包名被不同签名应用接管。 */
    private final Map<String, String> owners = new HashMap<>();
    /** 资源 ID + 目标包名 -> 目标签名与权限位集合。 */
    private final Map<String, Grant> grants = new HashMap<>();
    /**
     * 已有 ACL 文件损坏后禁止覆盖：否则一次坏读取可能把原有授权快照永久清空，
     * 也会掩盖待排查的文件损坏原因。原有 grant 不再从内存中生效。
     */
    private boolean readOnly;
    /** 单个被授权包的签名快照及其权限位。 */
    private static final class Grant {
        /** signature 为目标包签名摘要；rights 是 Wire.READ 等位掩码。 */
        final String signature;
        final int rights;
        Grant(String signature, int rights) { this.signature = signature; this.rights = rights; }
    }
    /** ACL 资源地址；数据资源 name 为 namespace，事件资源 name 为完整 topic。 */
    static final class Resource {
        /** type 区分数据/事件；scope/userId 区分设备或 Android 用户。 */
        final int type, scope, userId;
        /** owner 是资源所属包名，name 是 namespace 或 topic。 */
        final String owner, name;
        Resource(int type, int scope, int userId, String owner, String name) {
            // 设备级 DE 数据属于固定 user 0；用户级地址使用真实调用者所属 user。
            this.type = type; this.scope = scope; this.userId = scope == Wire.DEVICE_DE ? 0 : userId;
            this.owner = owner; this.name = name;
        }
        /** 持久化映射的稳定资源键，不含单条数据 Key 或存储后端类型。 */
        String id() { return type + "|" + scope + "|" + userId + "|" + owner + "|" + name; }
    }
    /** 创建并恢复 ACL 文件；已有文件损坏时 load 清空记录并禁止后续 ACL 写入。 */
    AccessController(Context context) {
        this.context = context;
        TLog.d(TAG, "initialize");
        // ACL 放在用户 0 的 system DE：system_server 可在 CE 未解锁时先恢复访问控制。
        File dir = new File(Environment.getDataSystemDeDirectory(0), "globaldata");
        if (!dir.exists()) {
            if (!dir.mkdirs() || !SELinux.restoreconRecursive(dir))
                throw new IllegalStateException("ACL directory");
        }
        file = new AtomicFile(new File(dir, "acl-v1.bin"));
        load();
        TLog.d(TAG, "loaded owners=" + owners.size() + " grants=" + grants.size()
                + " readOnly=" + readOnly);
    }
    /** 将单条数据地址映射到其 namespace 级 ACL 资源。 */
    static Resource data(StoreAddress key) {
        return new Resource(Wire.RESOURCE_DATA, key.scope, key.userId, key.owner, key.namespace);
    }
    /** 解析事件 scope/owner/topic，并把 userId 绑定到真实调用方。 */
    static Resource event(Bundle b, int callerUserId) {
        if (b == null) throw new IllegalArgumentException("missing event address");
        int scope = b.getInt(Wire.SCOPE);
        String owner = b.getString(Wire.OWNER), topic = b.getString(Wire.TOPIC);
        if (scope < Wire.DEVICE_DE || scope > Wire.USER_CE || !StoreAddress.name(owner, 180)
                || !StoreAddress.name(topic, 180)) throw new IllegalArgumentException("event address");
        return new Resource(Wire.RESOURCE_EVENT, scope, callerUserId, owner, topic);
    }
    /** 解析 grant/revoke 请求中的资源种类和名称。 */
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
    /**
     * 将常规资源名绑定到 owner 包名，并预留 sys.* 给平台代码；
     * 这样普通包不能借自定义名称伪装成别的包或系统资源。
     */
    private boolean validPrefix(Resource resource) {
        // sys.* 虽通过名称校验，仍会在 available 中要求平台身份。
        if (resource.name.startsWith("sys.")) return true;
        if (resource.type == Wire.RESOURCE_DATA)
            return resource.name.equals("share." + resource.owner)
                    || resource.name.equals("app." + resource.owner);
        return resource.name.startsWith("events." + resource.owner + "/");
    }
    /**
     * DEVICE_DE 跨 Android 用户共享，sys.* 又是系统命名空间；
     * 两类资源必须用系统 UID 或平台签名身份，不能只靠普通包名声明取得。
     */
    private boolean platformSigned(int uid) {
        return uid == Process.SYSTEM_UID || context.getPackageManager().checkSignatures(uid,
                Process.SYSTEM_UID) == PackageManager.SIGNATURE_MATCH;
    }
    /**
     * 对指定用户安装的包计算当前 APK 签名证书 SHA-256 集合摘要。
     * 多签名先排序再拼接，证书不可读取时返回 null，后续权限检查按拒绝处理。
     */
    private String signature(String packageName, int userId) {
        try {
            // 按资源所属 Android 用户查已安装包，避免误取另一个用户的包身份。
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
            // 多签名数组的返回顺序不应改变同一包的身份摘要。
            Arrays.sort(digests); return String.join(":", digests);
        } catch (Exception e) { return null; }
    }
    /** 核对包名确实属于 Binder 调用 UID，防止请求方自报任意 owner。 */
    private boolean uidOwns(int uid, String packageName) {
        String[] packages = context.getPackageManager().getPackagesForUid(uid);
        if (packages == null) return false;
        for (String candidate : packages) if (packageName.equals(candidate)) return true;
        return false;
    }
    /**
     * 在查询 owner/grant 之前先排除非法命名和受保护作用域；
     * 对 USER_CE 检查解锁状态，避免未解锁时访问凭据加密数据。
     */
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
    /**
     * 检查已有 owner 或 grant 是否覆盖 required 权限位。每次重新计算当前包签名，
     * 是为了让换签名重装的包立即失去旧 owner/grant 身份；仅匹配 UID 不足以做到这一点。
     * CE 锁定优先返回 LOCKED，其他身份或权限不匹配返回 PERMISSION_DENIED。
     */
    synchronized int check(int uid, Resource r, int required) {
        TLog.d(TAG, "check uid=" + uid + " " + TLog.address(r) + " required=" + required);
        // 1. CE 锁定单独返回 LOCKED，调用方可在用户解锁后重试。
        if (r.scope == Wire.USER_CE) {
            UserManager users = context.getSystemService(UserManager.class);
            if (users == null || !users.isUserUnlocked(UserHandle.of(r.userId))) {
                TLog.w(TAG, "locked uid=" + uid + " user=" + r.userId);
                return Wire.LOCKED;
            }
        }
        // 2. 在查看持久化 grant 前排除非法地址和设备/系统专属资源。
        if (!available(r, uid)) {
            TLog.w(TAG, "unavailable uid=" + uid + " scope=" + r.scope);
            return Wire.PERMISSION_DENIED;
        }
        // 3. owner 路径：包必须属于调用 UID；已有 owner 记录还必须匹配原签名。
        String ownerDigest = owners.get(r.id());
        // 未登记过的真实 owner 可通过当前包名与签名校验；已登记时必须匹配历史签名。
        if (uidOwns(uid, r.owner)) {
            String current = signature(r.owner, r.userId);
            if (current != null && (ownerDigest == null || current.equals(ownerDigest))) {
                TLog.d(TAG, "owner allowed uid=" + uid + " " + TLog.address(r));
                return Wire.OK;
            }
        }
        // 4. 共享 UID 可能对应多个包，逐一找当前签名仍匹配且覆盖全部请求位的 grant。
        String[] packages = context.getPackageManager().getPackagesForUid(uid);
        if (packages != null) for (String pkg : packages) {
            Grant grant = grants.get(r.id() + "|" + pkg);
            if (grant != null && (grant.rights & required) == required
                    && grant.signature.equals(signature(pkg, r.userId))) {
                TLog.d(TAG, "grant allowed uid=" + uid + " target=" + pkg
                        + " " + TLog.address(r));
                return Wire.OK;
            }
        }
        TLog.w(TAG, "permission denied uid=" + uid + " required=" + required);
        return Wire.PERMISSION_DENIED;
    }
    /**
     * 首次写入或变更授权时固定 owner 签名，使旧数据和授权长期属于最初签名者，
     * 而不是后来占用同一包名的应用。文件只读或持久化失败时撤回新内存记录，
     * 避免本次进程看似授权成功、重启后却丢失 owner 身份。
     */
    int ensureOwner(int uid, Resource r) {
        TLog.d(TAG, "ensureOwner uid=" + uid + " " + TLog.address(r));
        // 1. 先检查用户解锁、受保护作用域和 UID 真实持有的 owner 包名。
        if (r.scope == Wire.USER_CE) {
            UserManager users = context.getSystemService(UserManager.class);
            if (users == null || !users.isUserUnlocked(UserHandle.of(r.userId))) {
                TLog.w(TAG, "owner check locked uid=" + uid + " user=" + r.userId);
                return Wire.LOCKED;
            }
        }
        if (!available(r, uid) || !uidOwns(uid, r.owner)) {
            TLog.w(TAG, "owner check denied uid=" + uid + " reason=identity_or_scope");
            return Wire.PERMISSION_DENIED;
        }
        // 2. 对比持久化的首次签名；同包名换签名后不能接管旧资源。
        String digest = signature(r.owner, r.userId);
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
            if (readOnly) {
                TLog.e(TAG, "owner check unavailable reason=read_only");
                return Wire.STORAGE_UNAVAILABLE;
            }
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
        TLog.d(TAG, "authorize uid=" + uid + " right=" + right
                + " createOwner=" + createOwner + " " + TLog.address(r));
        // 共享写入者可使用 owner 的 grant，但只有真实 owner 能建立身份记录。
        if (createOwner && uidOwns(uid, r.owner)) {
            int status = ensureOwner(uid, r); if (status != Wire.OK) return status;
        }
        return check(uid, r, right);
    }
    /**
     * 仅资源 owner 可变更 grant。限制 rights 与资源类型匹配，避免把事件权限
     * 当作数据权限解释；授权绑定目标当前签名，避免同名包换签名后继承权限。
     * 持久化失败时恢复旧内存记录，防止进程内 ACL 与文件快照分叉。
     */
    synchronized int changeGrant(int uid, Resource r, String target, int rights, boolean grant) {
        TLog.d(TAG, (grant ? "grant" : "revoke") + " uid=" + uid + " " + TLog.address(r)
                + " target=" + target + " rights=" + rights);
        // 1. 权限位先按数据/事件资源类型收窄，拒绝混用和空操作。
        int allowed = r.type == Wire.RESOURCE_DATA
                ? Wire.READ | Wire.WRITE | Wire.DELETE | Wire.OBSERVE
                : Wire.PUBLISH | Wire.SUBSCRIBE;
        if (rights == 0 || (rights & ~allowed) != 0 || !StoreAddress.name(target, 180)) {
            TLog.w(TAG, "grant rejected uid=" + uid + " reason=invalid_request");
            return Wire.BAD_REQUEST;
        }
        // 2. 只有资源真实 owner 能修改 grant；目标包不能自己给自己提权。
        int owner = ensureOwner(uid, r);
        if (owner != Wire.OK) {
            TLog.w(TAG, "grant rejected uid=" + uid + " status=" + owner);
            return owner;
        }
        // 3. ACL 文件加载失败时不覆盖原文件，即使内存里看似能构造新记录。
        if (readOnly) {
            TLog.e(TAG, "grant unavailable reason=read_only");
            return Wire.STORAGE_UNAVAILABLE;
        }
        // 4. 授权前取目标包当前签名；包不存在时不创建待未来安装时生效的授权。
        String id = r.id() + "|" + target; Grant prior = grants.get(id);
        // digest 是目标包当前签名；重装并换签名的同名包不继承 prior.rights。
        String digest = grant ? signature(target, r.userId) : null;
        if (grant && digest == null) {
            TLog.d(TAG, "grant target not found target=" + target);
            return Wire.NOT_FOUND;
        }
        // 同名包换签名重装后不能继承旧权限位，只能使用本次重新授予的位。
        if (grant) grants.put(id, new Grant(digest,
                rights | (prior != null && digest.equals(prior.signature) ? prior.rights : 0)));
        else if (prior != null) {
            int remaining = prior.rights & ~rights;
            if (remaining == 0) grants.remove(id); else grants.put(id, new Grant(prior.signature, remaining));
        }
        // 5. 先修改内存候选记录，再整体持久化；失败时用 prior 恢复。
        try {
            persist();
            TLog.d(TAG, "grant record persisted target=" + target + " rights=" + rights);
            return Wire.OK;
        }
        catch (RuntimeException e) {
            TLog.e(TAG, "grant persist failed type=" + e.getClass().getSimpleName());
            if (prior == null) grants.remove(id); else grants.put(id, prior);
            return Wire.STORAGE_UNAVAILABLE;
        }
    }
    /**
     * 校验魔数、版本与记录数后恢复 ACL。损坏时清空 owner/grant 并禁止后续 ACL 写入；
     * 对真实 owner 的已有资源读取仍会走实时包名与签名校验，而不是恢复损坏的 grant。
     */
    private void load() {
        if (!file.getBaseFile().exists()
                && !new File(file.getBaseFile().getPath() + ".bak").exists()) return;
        try (FileInputStream raw = file.openRead(); DataInputStream in = new DataInputStream(raw)) {
            // 先验证文件身份和数量上限，再按同一格式顺序恢复 owner 与 grant。
            if (in.readInt() != MAGIC || in.readInt() != VERSION) throw new IOException("ACL format");
            int count = in.readInt(); if (count < 0 || count > 100000) throw new IOException("ACL count");
            for (int i = 0; i < count; i++) owners.put(in.readUTF(), in.readUTF());
            count = in.readInt(); if (count < 0 || count > 100000) throw new IOException("grant count");
            for (int i = 0; i < count; i++) grants.put(in.readUTF(), new Grant(in.readUTF(), in.readInt()));
        } catch (IOException e) {
            // 任一段损坏都不接受部分恢复的权限映射，也不覆盖现场文件。
            TLog.e(TAG, "load failed type=" + e.getClass().getSimpleName());
            owners.clear(); grants.clear(); readOnly = true; // 不恢复损坏的 grant，也不覆盖原 ACL 文件。
        }
    }
    /**
     * 将 owner 与 grant 作为同一个快照原子写入，使两类身份记录不会只更新一半；
     * 写入失败由 AtomicFile 回滚临时文件，上层再恢复对应内存映射。
     */
    private void persist() {
        FileOutputStream raw = null;
        try {
            // startWrite/finishWrite 使 owner 与 grant 作为一个完整快照替换旧文件。
            raw = file.startWrite(); DataOutputStream out = new DataOutputStream(raw);
            out.writeInt(MAGIC); out.writeInt(VERSION); out.writeInt(owners.size());
            for (Map.Entry<String, String> e : owners.entrySet()) { out.writeUTF(e.getKey()); out.writeUTF(e.getValue()); }
            out.writeInt(grants.size());
            for (Map.Entry<String, Grant> e : grants.entrySet()) {
                out.writeUTF(e.getKey()); out.writeUTF(e.getValue().signature); out.writeInt(e.getValue().rights);
            }
            out.flush(); file.finishWrite(raw);
        } catch (IOException e) {
            TLog.e(TAG, "commit failed type=" + e.getClass().getSimpleName());
            // AtomicFile 恢复旧文件；上层再撤销本次内存映射修改。
            if (raw != null) file.failWrite(raw);
            throw new IllegalStateException("ACL commit failed", e);
        }
    }
}
