package com.maplecode.util;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

/**
 * 文件 I/O 工具方法。
 */
public final class IoUtil {

    private IoUtil() {}

    /**
     * 原子写入：先写临时文件，再原子移动到目标路径。
     * 进程崩溃时不会损坏目标文件（要么是旧内容，要么是完整新内容）。
     *
     * @param target 目标文件路径
     * @param content 要写入的文本内容
     * @throws IOException 写入或移动失败
     */
    public static void atomicWrite(Path target, String content) throws IOException {
        Path dir = target.getParent();
        if (dir != null) {
            Files.createDirectories(dir);
        }
        Path tmp = Files.createTempFile(dir, ".maplecode-", ".tmp");
        try {
            Files.writeString(tmp, content, StandardCharsets.UTF_8);
            try {
                Files.move(tmp, target,
                    StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException e) {
            Files.deleteIfExists(tmp);
            throw e;
        }
    }
}
