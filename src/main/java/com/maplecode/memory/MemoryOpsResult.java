package com.maplecode.memory;

import java.util.List;

public record MemoryOpsResult(List<MemoryOp> ops) {
    public static MemoryOpsResult empty() {
        return new MemoryOpsResult(List.of());
    }
}
