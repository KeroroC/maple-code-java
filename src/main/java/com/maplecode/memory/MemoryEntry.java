package com.maplecode.memory;

public record MemoryEntry(
    String name,
    MemoryCategory category,
    String summary,
    String relativePath,
    String updated
) {}
