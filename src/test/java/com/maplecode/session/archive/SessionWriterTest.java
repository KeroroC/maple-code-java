package com.maplecode.session.archive;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.maplecode.provider.ContentBlock;
import com.maplecode.session.ChatSession;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class SessionWriterTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    @Test
    void writeSingleUserMessage(@TempDir Path tmp) throws Exception {
        ChatSession session = new ChatSession();
        session.appendUserText("你好");
        Path target = tmp.resolve("test.jsonl");

        new SessionWriter().write(session, target);

        List<String> lines = Files.readAllLines(target);
        assertEquals(1, lines.size());
        JsonNode root = JSON.readTree(lines.get(0));
        assertEquals("user", root.get("role").asText());
        JsonNode content = root.get("content");
        assertTrue(content.isArray());
        assertEquals(1, content.size());
        assertEquals("text", content.get(0).get("type").asText());
        assertEquals("你好", content.get(0).get("text").asText());
    }

    @Test
    void writeMixedMessages(@TempDir Path tmp) throws Exception {
        ChatSession session = new ChatSession();
        session.appendUserText("读取文件");
        session.appendAssistant(List.of(
            new ContentBlock.TextBlock("我来读取"),
            new ContentBlock.ToolUseBlock("tu_1", "read_file",
                JSON.createObjectNode().put("path", "a.java"))
        ));
        session.appendUser(List.of(
            new ContentBlock.ToolResultBlock("tu_1", "文件内容", false)
        ));
        Path target = tmp.resolve("test.jsonl");

        new SessionWriter().write(session, target);

        List<String> lines = Files.readAllLines(target);
        assertEquals(3, lines.size());

        JsonNode line2 = JSON.readTree(lines.get(1));
        assertEquals("assistant", line2.get("role").asText());
        assertEquals(2, line2.get("content").size());
        assertEquals("text", line2.get("content").get(0).get("type").asText());
        assertEquals("tool_use", line2.get("content").get(1).get("type").asText());
        assertEquals("tu_1", line2.get("content").get(1).get("id").asText());

        JsonNode line3 = JSON.readTree(lines.get(2));
        assertEquals("user", line3.get("role").asText());
        assertEquals("tool_result", line3.get("content").get(0).get("type").asText());
        assertEquals("tu_1", line3.get("content").get(0).get("toolUseId").asText());
        assertFalse(line3.get("content").get(0).get("isError").asBoolean());
    }

    @Test
    void writeEmptySessionReturnsZero(@TempDir Path tmp) throws Exception {
        ChatSession session = new ChatSession();
        Path target = tmp.resolve("empty.jsonl");

        int count = new SessionWriter().write(session, target);

        assertEquals(0, count);
        assertTrue(Files.exists(target));
        assertEquals(0, Files.size(target));
    }
}
