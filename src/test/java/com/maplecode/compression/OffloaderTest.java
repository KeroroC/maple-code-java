package com.maplecode.compression;

import com.maplecode.provider.ChatMessage;
import com.maplecode.provider.ChatMessage.Role;
import com.maplecode.provider.ContentBlock;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class OffloaderTest {

    private static final CompressionConfig CFG = new CompressionConfig(
        200_000, 13_000, 3_000,
        8_000, 30_000,
        10_000, 5,
        8, 4,
        3);

    private static ContentBlock.ToolResultBlock bigResult(int chars) {
        // Create multi-line content to trigger head/tail preview
        StringBuilder sb = new StringBuilder(chars);
        int lineLen = 100;
        int lines = chars / lineLen;
        for (int i = 0; i < lines; i++) {
            sb.append("line").append(i).append(" ").append("x".repeat(lineLen - 10)).append("\n");
        }
        // Pad remaining chars
        int remaining = chars - sb.length();
        if (remaining > 0) {
            sb.append("x".repeat(remaining));
        }
        return new ContentBlock.ToolResultBlock("tu-1", sb.toString(), false);
    }

    @Test
    void singleBigResultGetsOffloaded(@TempDir Path tmp) throws Exception {
        var storage = new CompressionStorage(tmp.resolve("s"));
        var offloader = new Offloader(storage);
        var msg = new ChatMessage(Role.USER, List.of(bigResult(40_000))); // 10K tokens
        var out = offloader.apply(List.of(msg), CFG);
        var trb = (ContentBlock.ToolResultBlock) out.get(0).blocks().get(0);
        assertNotEquals(40_000, trb.content().length());
        assertTrue(trb.content().contains("[Offloaded to"));
        assertTrue(trb.content().contains("--- head ---"));
    }

    @Test
    void shortContentPreviewSkipsHeadTail(@TempDir Path tmp) throws Exception {
        var storage = new CompressionStorage(tmp.resolve("s"));
        var offloader = new Offloader(storage);
        // 8K tokens = 32K chars → 超 single threshold, but short lines
        String content = "line1\nline2\nline3\nline4\nline5";
        var block = new ContentBlock.ToolResultBlock("tu-1", content + "x".repeat(32_000), false);
        var msg = new ChatMessage(Role.USER, List.of(block));
        var out = offloader.apply(List.of(msg), CFG);
        var trb = (ContentBlock.ToolResultBlock) out.get(0).blocks().get(0);
        assertFalse(trb.content().contains("--- head ---"));
    }

    @Test
    void assistantMessagesUnchanged(@TempDir Path tmp) throws Exception {
        var storage = new CompressionStorage(tmp.resolve("s"));
        var offloader = new Offloader(storage);
        var asst = new ChatMessage(Role.ASSISTANT,
            List.of(new ContentBlock.TextBlock("x".repeat(100_000))));
        var out = offloader.apply(List.of(asst), CFG);
        assertSame(asst, out.get(0));
    }

    @Test
    void toolUseBlockUnchanged(@TempDir Path tmp) throws Exception {
        var storage = new CompressionStorage(tmp.resolve("s"));
        var offloader = new Offloader(storage);
        var tue = new ContentBlock.ToolUseBlock("tu-1", "read_file",
            new com.fasterxml.jackson.databind.ObjectMapper().createObjectNode().put("path", "/foo"));
        var msg = new ChatMessage(Role.ASSISTANT, List.of(tue));
        var out = offloader.apply(List.of(msg), CFG);
        var nue = (ContentBlock.ToolUseBlock) out.get(0).blocks().get(0);
        assertEquals(tue, nue);
    }
}
