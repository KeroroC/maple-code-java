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
