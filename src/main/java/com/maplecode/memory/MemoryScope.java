package com.maplecode.memory;

import java.nio.file.Path;
import java.nio.file.Paths;

public enum MemoryScope {
    USER(Paths.get(System.getProperty("user.home"), ".maplecode", "memory")),
    PROJECT(Paths.get(System.getProperty("user.dir"), ".maplecode", "memory"));

    private final Path basePath;

    MemoryScope(Path basePath) {
        this.basePath = basePath;
    }

    public Path basePath() { return basePath; }
}
