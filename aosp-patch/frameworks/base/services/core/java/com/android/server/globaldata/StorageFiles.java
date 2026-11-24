package com.android.server.globaldata;

import android.util.AtomicFile;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.NoSuchFileException;
import java.nio.file.attribute.BasicFileAttributes;

final class StorageFiles {
    private StorageFiles() {}

    static File sibling(AtomicFile file, String suffix) {
        return new File(file.getBaseFile().getPath() + suffix);
    }

    // 区分真的不存在和读取失败，否则不可读的旧数据可能被当作首次初始化。
    static boolean exists(File file) throws IOException {
        try {
            BasicFileAttributes attrs = Files.readAttributes(
                    file.toPath(), BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
            if (!attrs.isRegularFile()) throw new IOException("expected a regular file");
            return true;
        } catch (NoSuchFileException missing) {
            return false;
        }
    }

    static File committedSource(AtomicFile file) throws IOException {
        // 旧版 AtomicFile 的 .bak 是最后一次提交，直接读取可保留故障现场。
        File backup = sibling(file, ".bak");
        if (exists(backup)) return backup;
        return exists(file.getBaseFile()) ? file.getBaseFile() : null;
    }

    static void finishWrite(AtomicFile file, FileOutputStream stream) throws IOException {
        // 若旧 .bak 仍在，重载会优先读它，不能将替换报告为成功。
        if (exists(sibling(file, ".bak"))) throw new IOException("legacy backup was not restored");
        // AtomicFile 只记录 sync 和 rename 失败，调用方需确认写入结果。
        stream.getFD().sync();
        file.finishWrite(stream);
        if (exists(sibling(file, ".new")) || !exists(file.getBaseFile())) {
            throw new IOException("atomic replacement did not complete");
        }
    }
}
