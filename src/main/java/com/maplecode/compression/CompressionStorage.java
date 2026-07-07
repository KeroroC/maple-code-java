package com.maplecode.compression;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 会话级文件存储。将 offload 的 tool result 落盘到 {@code ~/.maplecode/cache/<session-uuid>/}。
 * T5 会用 TDD 完整实现，此处仅为编译桩。
 */
public class CompressionStorage {

    private final Path sessionDir;
    private final AtomicInteger seq = new AtomicInteger();

    public CompressionStorage(Path sessionDir) {
        this.sessionDir = sessionDir;
    }

    public Path write(String content) throws IOException {
        Files.createDirectories(sessionDir);
        String filename = "offload-" + seq.incrementAndGet() + ".txt";
        Path file = sessionDir.resolve(filename);
        Files.writeString(file, content);
        return file;
    }

    public String buildPreview(Path file, String content, int headLines, int tailLines) {
        String[] lines = content.split("\n", -1);
        if (lines.length <= headLines + tailLines) {
            return content;
        }
        StringBuilder sb = new StringBuilder();
        sb.append("--- head ---\n");
        for (int i = 0; i < headLines; i++) {
            sb.append(lines[i]).append("\n");
        }
        sb.append("--- tail ---\n");
        for (int i = lines.length - tailLines; i < lines.length; i++) {
            sb.append(lines[i]).append("\n");
        }
        return sb.toString();
    }

    public void close() throws IOException {
        if (Files.exists(sessionDir)) {
            try (var walk = Files.walk(sessionDir)) {
                // walk reversed to delete children before parent
                walk.sorted(java.util.Comparator.reverseOrder())
                    .forEach(p -> {
                        try { Files.deleteIfExists(p); } catch (IOException ignored) {}
                    });
            }
        }
    }
}
