package com.maplecode.session.archive;

import com.maplecode.provider.ChatMessage;
import com.maplecode.provider.ContentBlock;
import com.maplecode.session.ChatSession;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class SessionArchiveTest {

    @Test
    void saveThenLoadRoundtrip(@TempDir Path tmp) {
        SessionArchive archive = new SessionArchive(tmp);
        ChatSession session = new ChatSession();
        session.appendUserText("你好");
        session.appendAssistant(List.of(new ContentBlock.TextBlock("你好！")));

        String id = archive.save(session);
        assertNotNull(id);

        List<ChatMessage> loaded = archive.load(id);
        assertEquals(2, loaded.size());
        assertEquals("你好", ((ContentBlock.TextBlock) loaded.get(0).blocks().get(0)).text());
        assertEquals("你好！", ((ContentBlock.TextBlock) loaded.get(1).blocks().get(0)).text());
    }

    @Test
    void saveEmptySessionReturnsNull(@TempDir Path tmp) {
        SessionArchive archive = new SessionArchive(tmp);
        ChatSession session = new ChatSession();

        String id = archive.save(session);
        assertNull(id);
    }

    @Test
    void listRecentSortedByMtime(@TempDir Path tmp) throws Exception {
        SessionArchive archive = new SessionArchive(tmp);
        ChatSession s1 = new ChatSession();
        s1.appendUserText("first");
        archive.save(s1);
        Thread.sleep(100);
        ChatSession s2 = new ChatSession();
        s2.appendUserText("second");
        archive.save(s2);

        List<SessionMeta> recent = archive.listRecent(10);
        assertEquals(2, recent.size());
        // mtime 应该是递减的（最新的在前）
        assertFalse(recent.get(0).lastActivity().isBefore(recent.get(1).lastActivity()));
    }

    @Test
    void cleanExpiredDeletesOldFiles(@TempDir Path tmp) throws Exception {
        SessionArchive archive = new SessionArchive(tmp);
        ChatSession session = new ChatSession();
        session.appendUserText("old");
        archive.save(session);

        // 设置文件 mtime 为 31 天前
        Path file;
        try (var s = Files.list(tmp)) { file = s.filter(p -> p.toString().endsWith(".jsonl")).findFirst().orElseThrow(); }
        Instant oldTime = Instant.now().minus(Duration.ofDays(31));
        file.toFile().setLastModified(oldTime.toEpochMilli());

        int cleaned = archive.cleanExpired(Duration.ofDays(30));
        assertEquals(1, cleaned);
        assertFalse(Files.exists(file));
    }

    @Test
    void loadByPrefix(@TempDir Path tmp) {
        SessionArchive archive = new SessionArchive(tmp);
        ChatSession session = new ChatSession();
        session.appendUserText("hello");
        String id = archive.save(session);

        String prefix = id.substring(0, 10);
        List<ChatMessage> loaded = archive.load(prefix);
        assertEquals(1, loaded.size());
    }

    @Test
    void loadNonexistentThrows(@TempDir Path tmp) {
        SessionArchive archive = new SessionArchive(tmp);
        assertThrows(SessionArchiveException.class, () -> archive.load("nonexistent"));
    }

    @Test
    void loadAmbiguousPrefixThrows(@TempDir Path tmp) {
        SessionArchive archive = new SessionArchive(tmp);
        ChatSession s1 = new ChatSession();
        s1.appendUserText("a");
        archive.save(s1);
        ChatSession s2 = new ChatSession();
        s2.appendUserText("b");
        archive.save(s2);
        // 用短前缀（如 "20"）匹配多个 → 应抛异常
        assertThrows(SessionArchiveException.class, () -> archive.load("20"));
    }
}
