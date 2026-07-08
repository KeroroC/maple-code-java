package com.maplecode.compact;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 会话级文件存储。将 offload 的 tool result 落盘到 sessionDir。
 * 文件名格式：UUID-seq.txt（防冲突 + 可排序）。
 */
public final class CompactStorage {

    private final Path sessionDir;
    private final AtomicLong seq = new AtomicLong();

    public CompactStorage(Path sessionDir) {
        this.sessionDir = sessionDir;
        try {
            Files.createDirectories(sessionDir);
        } catch (IOException e) {
            throw new CompactException("cannot create session dir: " + sessionDir, e);
        }
    }

    /**
     * 将内容写入 sessionDir 下的唯一文件，返回文件路径。
     */
    public Path write(String content) {
        long n = seq.incrementAndGet();
        String name = UUID.randomUUID() + "-" + n + ".txt";
        Path target = sessionDir.resolve(name);
        try {
            Files.writeString(target, content);
            return target;
        } catch (IOException e) {
            throw new CompactException("offload write failed: " + target, e);
        }
    }

    /**
     * 构造 preview 文本。短内容直接展示；长内容截取 head + tail 并附元数据。
     */
    public String buildPreview(Path savedPath, String content, int headLines, int tailLines) {
        String[] lines = content.split("\n", -1);
        long bytes = content.getBytes().length;
        long lineCount = lines.length;
        String abs = savedPath.toAbsolutePath().toString();

        StringBuilder sb = new StringBuilder();
        sb.append("[Offloaded to ").append(abs)
          .append(" — ").append(bytes).append(" bytes, ")
          .append(lineCount).append(" lines]\n");

        if (lineCount <= headLines + tailLines) {
            sb.append(content);
        } else {
            sb.append("--- head ---\n");
            for (int i = 0; i < headLines; i++) {
                sb.append(lines[i]).append('\n');
            }
            sb.append("--- tail ---\n");
            for (int i = (int) lineCount - tailLines; i < lineCount; i++) {
                sb.append(lines[i]).append('\n');
            }
        }

        sb.append("[End of preview; re-read from path above for full content]");
        return sb.toString();
    }

    /**
     * 删除 sessionDir 及其所有内容。幂等，目录不存在则跳过。
     */
    public void close() {
        if (!Files.exists(sessionDir)) return;
        try (var walk = Files.walk(sessionDir)) {
            walk.sorted((a, b) -> b.compareTo(a))
                .forEach(p -> {
                    try { Files.deleteIfExists(p); }
                    catch (IOException ignored) { }
                });
        } catch (IOException ignored) { }
    }
}
