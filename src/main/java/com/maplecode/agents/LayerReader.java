package com.maplecode.agents;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

public final class LayerReader {

    private LayerReader() {}

    public static Layer read(Layer empty) {
        Path path = empty.absolutePath();

        if (!Files.exists(path)) {
            return Layer.empty(path);  // 不写 WARN：缺文件是常见
        }
        if (!Files.isRegularFile(path)) {
            System.err.println("[agents-md] " + path + ": not a regular file");
            return Layer.empty(path);
        }
        try {
            // 拒绝符号链接：AGENTS.md 本身不应是 symlink，防止读取仓库外文件
            if (Files.isSymbolicLink(path)) {
                Path realPath = path.toRealPath();
                System.err.println("[agents-md] " + path + ": symlink detected, resolved to " + realPath
                    + " — refusing to follow symlink");
                return Layer.empty(path);
            }
            long size = Files.size(path);
            if (size > IncludeLimits.defaults().maxFileSize()) {
                System.err.println("[agents-md] " + path + ": file too large: "
                    + size + " bytes (max " + IncludeLimits.defaults().maxFileSize() + ")");
                return Layer.empty(path);
            }
            String content = Files.readString(path);
            return new Layer(path, content, true);
        } catch (IOException e) {
            System.err.println("[agents-md] " + path + ": read failed: "
                + e.getClass().getSimpleName() + ": " + e.getMessage());
            return Layer.empty(path);
        }
    }
}
