package com.maplecode.agents;

import org.junit.jupiter.api.Test;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class LayerTest {

    @Test
    void emptyFactoryReturnsNotExists() {
        Layer layer = Layer.empty(Path.of("/tmp/foo.md"));
        assertFalse(layer.exists());
        assertEquals("", layer.content());
        assertEquals(Path.of("/tmp/foo.md"), layer.absolutePath());
    }

    @Test
    void recordAccessorsWork() {
        Layer layer = new Layer(Path.of("/a/b.md"), "hello", true);
        assertEquals(Path.of("/a/b.md"), layer.absolutePath());
        assertEquals("hello", layer.content());
        assertTrue(layer.exists());
    }
}
