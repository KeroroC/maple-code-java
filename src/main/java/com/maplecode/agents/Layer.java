package com.maplecode.agents;

import java.nio.file.Path;

public record Layer(Path absolutePath, String content, boolean exists) {
    public static Layer empty(Path path) {
        return new Layer(path, "", false);
    }
}
