package com.maplecode.memory;

import java.util.Arrays;

public enum MemoryCategory {
    USER("user", MemoryScope.USER),
    FEEDBACK("feedback", MemoryScope.USER),
    PROJECT("project", MemoryScope.PROJECT),
    REFERENCE("reference", MemoryScope.PROJECT);

    private final String dirName;
    private final MemoryScope scope;

    MemoryCategory(String dirName, MemoryScope scope) {
        this.dirName = dirName;
        this.scope = scope;
    }

    public String dirName() { return dirName; }
    public MemoryScope scope() { return scope; }

    public static MemoryCategory fromDirName(String dirName) {
        return Arrays.stream(values())
            .filter(c -> c.dirName.equals(dirName))
            .findFirst()
            .orElseThrow(() -> new IllegalArgumentException("unknown category dir: " + dirName));
    }
}
