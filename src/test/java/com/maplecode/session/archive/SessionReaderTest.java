package com.maplecode.session.archive;

import com.maplecode.provider.ChatMessage;
import com.maplecode.provider.ContentBlock;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class SessionReaderTest {

    @Test
    void readNormalJsonl(@TempDir Path tmp) throws Exception {
        Path file = tmp.resolve("test.jsonl");
        Files.write(file, List.of(
            "{\"role\":\"user\",\"content\":[{\"type\":\"text\",\"text\":\"你好\"}]}",
            "{\"role\":\"assistant\",\"content\":[{\"type\":\"text\",\"text\":\"你好！\"}]}"
        ));

        List<ChatMessage> messages = new SessionReader().read(file);

        assertEquals(2, messages.size());
        assertEquals(ChatMessage.Role.USER, messages.get(0).role());
        assertEquals("你好", ((ContentBlock.TextBlock) messages.get(0).blocks().get(0)).text());
        assertEquals(ChatMessage.Role.ASSISTANT, messages.get(1).role());
        assertEquals("你好！", ((ContentBlock.TextBlock) messages.get(1).blocks().get(0)).text());
    }

    @Test
    void readMixedContentBlocks(@TempDir Path tmp) throws Exception {
        Path file = tmp.resolve("mixed.jsonl");
        Files.write(file, List.of(
            "{\"role\":\"assistant\",\"content\":[{\"type\":\"text\",\"text\":\"读取\"},{\"type\":\"tool_use\",\"id\":\"tu_1\",\"name\":\"read_file\",\"input\":{\"path\":\"a.java\"}}]}",
            "{\"role\":\"user\",\"content\":[{\"type\":\"tool_result\",\"toolUseId\":\"tu_1\",\"content\":\"文件内容\",\"isError\":false}]}"
        ));

        List<ChatMessage> messages = new SessionReader().read(file);

        assertEquals(2, messages.size());
        assertEquals(2, messages.get(0).blocks().size());
        assertInstanceOf(ContentBlock.TextBlock.class, messages.get(0).blocks().get(0));
        assertInstanceOf(ContentBlock.ToolUseBlock.class, messages.get(0).blocks().get(1));
        ContentBlock.ToolUseBlock toolUse = (ContentBlock.ToolUseBlock) messages.get(0).blocks().get(1);
        assertEquals("tu_1", toolUse.id());
        assertEquals("read_file", toolUse.name());

        assertEquals(1, messages.get(1).blocks().size());
        assertInstanceOf(ContentBlock.ToolResultBlock.class, messages.get(1).blocks().get(0));
        ContentBlock.ToolResultBlock result = (ContentBlock.ToolResultBlock) messages.get(1).blocks().get(0);
        assertEquals("tu_1", result.toolUseId());
        assertFalse(result.isError());
    }

    @Test
    void skipMalformedLines(@TempDir Path tmp) throws Exception {
        Path file = tmp.resolve("bad.jsonl");
        Files.write(file, List.of(
            "{\"role\":\"user\",\"content\":[{\"type\":\"text\",\"text\":\"ok\"}]}",
            "this is not valid json",
            "{\"role\":\"assistant\",\"content\":[{\"type\":\"text\",\"text\":\"fine\"}]}"
        ));

        List<ChatMessage> messages = new SessionReader().read(file);

        assertEquals(2, messages.size());
        assertEquals("ok", ((ContentBlock.TextBlock) messages.get(0).blocks().get(0)).text());
        assertEquals("fine", ((ContentBlock.TextBlock) messages.get(1).blocks().get(0)).text());
    }

    @Test
    void truncateOrphanToolUse(@TempDir Path tmp) throws Exception {
        Path file = tmp.resolve("orphan.jsonl");
        Files.write(file, List.of(
            "{\"role\":\"assistant\",\"content\":[{\"type\":\"text\",\"text\":\"调用工具\"},{\"type\":\"tool_use\",\"id\":\"tu_1\",\"name\":\"read_file\",\"input\":{\"path\":\"a.java\"}}]}",
            "{\"role\":\"assistant\",\"content\":[{\"type\":\"text\",\"text\":\"没有对应的 tool_result\"}]}"
        ));

        List<ChatMessage> messages = new SessionReader().read(file);

        assertEquals(2, messages.size());
        // 第 1 条消息：tool_use 被截断，只剩 text
        assertEquals(1, messages.get(0).blocks().size());
        assertInstanceOf(ContentBlock.TextBlock.class, messages.get(0).blocks().get(0));
    }

    @Test
    void truncateOrphanToolResult(@TempDir Path tmp) throws Exception {
        Path file = tmp.resolve("orphan-result.jsonl");
        // 反向孤儿：有 tool_result 但没有对应的 tool_use
        Files.write(file, List.of(
            "{\"role\":\"user\",\"content\":[{\"type\":\"text\",\"text\":\"你好\"}]}",
            "{\"role\":\"user\",\"content\":[{\"type\":\"tool_result\",\"toolUseId\":\"tu_missing\",\"content\":\"文件内容\",\"isError\":false}]}"
        ));

        List<ChatMessage> messages = new SessionReader().read(file);

        assertEquals(2, messages.size());
        // 第 1 条消息：正常保留
        assertEquals(1, messages.get(0).blocks().size());
        assertInstanceOf(ContentBlock.TextBlock.class, messages.get(0).blocks().get(0));
        // 第 2 条消息：反向孤儿 tool_result 应该被删除
        assertTrue(messages.get(1).blocks().isEmpty());
    }

    @Test
    void readEmptyFileReturnsEmptyList(@TempDir Path tmp) throws Exception {
        Path file = tmp.resolve("empty.jsonl");
        Files.createFile(file);

        List<ChatMessage> messages = new SessionReader().read(file);

        assertTrue(messages.isEmpty());
    }
}
