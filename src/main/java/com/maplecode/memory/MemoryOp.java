package com.maplecode.memory;

public sealed interface MemoryOp {
    record Create(MemoryCategory category, String name, String content) implements MemoryOp {}
    record Update(String name, String content) implements MemoryOp {}
    record Delete(String name) implements MemoryOp {}
}
