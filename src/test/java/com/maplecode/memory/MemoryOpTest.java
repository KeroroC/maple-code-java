package com.maplecode.memory;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class MemoryOpTest {

    @Test
    void createHasAllFields() {
        var op = new MemoryOp.Create(MemoryCategory.USER, "prefer-java21", "content");
        assertEquals(MemoryCategory.USER, op.category());
        assertEquals("prefer-java21", op.name());
        assertEquals("content", op.content());
    }

    @Test
    void updateHasNameAndContent() {
        var op = new MemoryOp.Update("prefer-java21", "updated content");
        assertEquals("prefer-java21", op.name());
        assertEquals("updated content", op.content());
    }

    @Test
    void deleteHasOnlyName() {
        var op = new MemoryOp.Delete("prefer-java21");
        assertEquals("prefer-java21", op.name());
    }

    @Test
    void sealed_hierarchy() {
        MemoryOp op = new MemoryOp.Create(MemoryCategory.USER, "x", "y");
        assertTrue(op instanceof MemoryOp.Create);
        assertFalse(op instanceof MemoryOp.Update);
        assertFalse(op instanceof MemoryOp.Delete);
    }
}
