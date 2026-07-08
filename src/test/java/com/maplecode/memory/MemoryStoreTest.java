package com.maplecode.memory;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class MemoryStoreTest {

    @TempDir
    Path userDir;

    @TempDir
    Path projectDir;

    MemoryStore store;

    @BeforeEach
    void setUp() {
        store = new MemoryStore(userDir, projectDir);
    }

    @Test
    void loadIndex_returnsEmpty_whenNoMemoryMd() {
        List<MemoryEntry> entries = store.loadIndex(MemoryScope.USER);
        assertTrue(entries.isEmpty());
    }

    @Test
    void createThenLoadIndex_roundTrips() {
        store.executeOp(new MemoryOp.Create(MemoryCategory.USER, "prefer-java21", "用户偏好 Java 21"));
        List<MemoryEntry> entries = store.loadIndex(MemoryScope.USER);
        assertEquals(1, entries.size());
        assertEquals("prefer-java21", entries.get(0).name());
        assertEquals(MemoryCategory.USER, entries.get(0).category());
    }

    @Test
    void updateOverwritesContent() {
        store.executeOp(new MemoryOp.Create(MemoryCategory.USER, "pref", "original"));
        store.executeOp(new MemoryOp.Update("pref", "updated"));
        String content = store.readContent(MemoryScope.USER, MemoryCategory.USER, "pref");
        assertTrue(content.contains("updated"));
    }

    @Test
    void deleteRemovesFile() {
        store.executeOp(new MemoryOp.Create(MemoryCategory.PROJECT, "spring", "uses spring"));
        store.executeOp(new MemoryOp.Delete("spring"));
        List<MemoryEntry> entries = store.loadIndex(MemoryScope.PROJECT);
        assertTrue(entries.isEmpty());
    }

    @Test
    void clearAll_removesEverything() {
        store.executeOp(new MemoryOp.Create(MemoryCategory.USER, "a", "content a"));
        store.executeOp(new MemoryOp.Create(MemoryCategory.FEEDBACK, "b", "content b"));
        store.clearAll(MemoryScope.USER);
        assertTrue(store.loadIndex(MemoryScope.USER).isEmpty());
    }

    @Test
    void fileNaming_incrementsSequence() {
        store.executeOp(new MemoryOp.Create(MemoryCategory.USER, "first", "c1"));
        store.executeOp(new MemoryOp.Create(MemoryCategory.USER, "second", "c2"));
        List<MemoryEntry> entries = store.loadIndex(MemoryScope.USER);
        assertEquals(2, entries.size());
        assertTrue(entries.get(0).relativePath().contains("001"));
        assertTrue(entries.get(1).relativePath().contains("002"));
    }

    @Test
    void readContent_returnsNull_forNonExistent() {
        assertNull(store.readContent(MemoryScope.USER, MemoryCategory.USER, "nope"));
    }
}
