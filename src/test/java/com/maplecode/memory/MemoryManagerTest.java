package com.maplecode.memory;

import com.maplecode.provider.ChatMessage;
import com.maplecode.provider.ContentBlock;
import com.maplecode.provider.LlmProvider;
import com.maplecode.provider.StreamChunk;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class MemoryManagerTest {

    @TempDir
    Path userDir;

    @TempDir
    Path projectDir;

    @Test
    void extractSync_callsProviderAndWritesFiles() {
        AtomicInteger callCount = new AtomicInteger();
        LlmProvider mockProvider = (req, sink) -> {
            callCount.incrementAndGet();
            sink.accept(new StreamChunk.TextDelta(
                "{\"ops\": [{\"action\": \"create\", \"category\": \"user\", \"name\": \"test\", \"content\": \"test content\"}]}"));
        };
        var config = new MemoryConfig(true, "test-model", 10);
        var store = new MemoryStore(userDir, projectDir);
        var manager = new MemoryManager(config, mockProvider, store, "main-model");

        var msgs = List.of(
            new ChatMessage(ChatMessage.Role.USER, List.of(new ContentBlock.TextBlock("hi")))
        );
        manager.extractSync(msgs);

        assertEquals(1, callCount.get());
        List<MemoryEntry> entries = store.loadIndex(MemoryScope.USER);
        assertEquals(1, entries.size());
        assertEquals("test", entries.get(0).name());
    }

    @Test
    void extractSync_skipped_whenCircuitOpen() {
        LlmProvider failingProvider = (req, sink) -> {
            throw new RuntimeException("provider error");
        };
        var config = new MemoryConfig(true, "test-model", 10);
        var store = new MemoryStore(userDir, projectDir);
        var manager = new MemoryManager(config, failingProvider, store, "main-model");

        var msgs = List.of(
            new ChatMessage(ChatMessage.Role.USER, List.of(new ContentBlock.TextBlock("hi")))
        );

        // 3 次失败触发熔断
        manager.extractSync(msgs);
        manager.extractSync(msgs);
        manager.extractSync(msgs);

        // 第 4 次应被跳过（不抛异常）
        manager.extractSync(msgs);
        assertTrue(store.loadIndex(MemoryScope.USER).isEmpty());
    }

    @Test
    void listMemories_returnsFormattedString() {
        var config = new MemoryConfig(true, null, 10);
        var store = new MemoryStore(userDir, projectDir);
        store.executeOp(new MemoryOp.Create(MemoryCategory.USER, "test", "test content"));
        var manager = new MemoryManager(config, null, store, "main-model");

        String list = manager.listMemories();
        assertTrue(list.contains("test"));
    }

    @Test
    void clearAll_removesAllMemories() {
        var config = new MemoryConfig(true, null, 10);
        var store = new MemoryStore(userDir, projectDir);
        store.executeOp(new MemoryOp.Create(MemoryCategory.USER, "a", "content"));
        var manager = new MemoryManager(config, null, store, "main-model");

        manager.clearAll();
        assertTrue(store.loadIndex(MemoryScope.USER).isEmpty());
        assertTrue(store.loadIndex(MemoryScope.PROJECT).isEmpty());
    }
}
