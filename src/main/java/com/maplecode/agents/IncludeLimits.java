package com.maplecode.agents;

public record IncludeLimits(
    int maxDepth,
    int maxFileSize,
    int maxTotalBytes
) {
    public static IncludeLimits defaults() {
        return new IncludeLimits(3, 1_048_576, 65_536);
    }
}
