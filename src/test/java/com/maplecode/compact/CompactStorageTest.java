package com.maplecode.compact;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.*;

class CompactStorageTest {

    @Test
    void writeReturnsPathUnderSessionDir(@TempDir Path tmp) throws Exception {
        var storage = new CompactStorage(tmp.resolve("session-abc"));
        Path saved = storage.write("hello world");
        assertTrue(Files.exists(saved));
        assertEquals("hello world", Files.readString(saved));
        assertTrue(saved.startsWith(tmp.resolve("session-abc")));
    }

    @Test
    void filenameIncludesUuidAndSeq(@TempDir Path tmp) throws Exception {
        var storage = new CompactStorage(tmp.resolve("session-abc"));
        Path a = storage.write("a");
        Path b = storage.write("b");
        assertNotEquals(a, b);
        assertTrue(a.getFileName().toString().endsWith(".txt"));
        assertTrue(b.getFileName().toString().endsWith(".txt"));
    }

    @Test
    void previewShortContentHasNoHeadTailSplit(@TempDir Path tmp) throws Exception {
        var storage = new CompactStorage(tmp.resolve("session-abc"));
        Path saved = storage.write("line1\nline2\nline3");
        String preview = storage.buildPreview(saved, "line1\nline2\nline3", 8, 4);
        assertTrue(preview.contains("line1"));
        assertTrue(preview.contains("line3"));
        assertFalse(preview.contains("--- head ---"));
        assertFalse(preview.contains("--- tail ---"));
    }

    @Test
    void previewLongContentHasHeadAndTail(@TempDir Path tmp) throws Exception {
        var storage = new CompactStorage(tmp.resolve("session-abc"));
        String content = IntStream.rangeClosed(1, 100).mapToObj(i -> "line" + i)
            .reduce((a, b) -> a + "\n" + b).orElseThrow();
        Path saved = storage.write(content);
        String preview = storage.buildPreview(saved, content, 3, 2);
        assertTrue(preview.contains("--- head ---"));
        assertTrue(preview.contains("--- tail ---"));
        assertTrue(preview.contains("line1"));   // head
        assertTrue(preview.contains("line100")); // tail
        assertFalse(preview.contains("line50")); // middle 被截
    }

    @Test
    void previewIncludesMetadata(@TempDir Path tmp) throws Exception {
        var storage = new CompactStorage(tmp.resolve("session-abc"));
        Path saved = storage.write("a\nb\nc");
        String preview = storage.buildPreview(saved, "a\nb\nc", 8, 4);
        assertTrue(preview.contains(saved.toAbsolutePath().toString()));
        assertTrue(preview.contains("bytes"));
        assertTrue(preview.contains("lines"));
    }

    @Test
    void closeDeletesSessionDir(@TempDir Path tmp) throws Exception {
        Path sessionDir = tmp.resolve("session-xyz");
        var storage = new CompactStorage(sessionDir);
        storage.write("x");
        assertTrue(Files.exists(sessionDir));
        storage.close();
        assertFalse(Files.exists(sessionDir));
    }
}
