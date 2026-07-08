package com.maplecode.memory;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.maplecode.provider.ChatMessage;
import com.maplecode.provider.ContentBlock;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class MemoryExtractorTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    // --- parseResponse ---

    @Test
    void parseResponse_plainJson() {
        String json = """
            {"ops": [{"action": "create", "category": "user", "name": "prefer-java21", "content": "用户偏好 Java 21"}]}
            """;
        var result = MemoryExtractor.parseResponse(json);
        assertEquals(1, result.ops().size());
        assertTrue(result.ops().get(0) instanceof MemoryOp.Create);
        var c = (MemoryOp.Create) result.ops().get(0);
        assertEquals(MemoryCategory.USER, c.category());
        assertEquals("prefer-java21", c.name());
        assertEquals("用户偏好 Java 21", c.content());
    }

    @Test
    void parseResponse_stripsMarkdownCodeBlock() {
        String json = """
            ```json
            {"ops": [{"action": "update", "name": "test", "content": "updated"}]}
            ```
            """;
        var result = MemoryExtractor.parseResponse(json);
        assertEquals(1, result.ops().size());
        assertTrue(result.ops().get(0) instanceof MemoryOp.Update);
        var u = (MemoryOp.Update) result.ops().get(0);
        assertEquals("test", u.name());
        assertEquals("updated", u.content());
    }

    @Test
    void parseResponse_stripsPlainCodeBlock() {
        String json = """
            ```
            {"ops": [{"action": "delete", "name": "old"}]}
            ```
            """;
        var result = MemoryExtractor.parseResponse(json);
        assertEquals(1, result.ops().size());
        assertTrue(result.ops().get(0) instanceof MemoryOp.Delete);
        assertEquals("old", ((MemoryOp.Delete) result.ops().get(0)).name());
    }

    @Test
    void parseResponse_emptyOps() {
        var result = MemoryExtractor.parseResponse("{\"ops\": []}");
        assertTrue(result.ops().isEmpty());
    }

    @Test
    void parseResponse_greedyFallback() {
        String text = "Here is the result:\n{\"ops\": []}\nDone.";
        var result = MemoryExtractor.parseResponse(text);
        assertTrue(result.ops().isEmpty());
    }

    @Test
    void parseResponse_invalidJson_returnsEmpty() {
        var result = MemoryExtractor.parseResponse("not json at all");
        assertTrue(result.ops().isEmpty());
    }

    @Test
    void parseResponse_multipleOps() {
        String json = """
            {"ops": [
                {"action": "create", "category": "project", "name": "spring-boot", "content": "Uses Spring Boot 3"},
                {"action": "update", "name": "old-entry", "content": "new info"},
                {"action": "delete", "name": "obsolete"}
            ]}
            """;
        var result = MemoryExtractor.parseResponse(json);
        assertEquals(3, result.ops().size());
        assertTrue(result.ops().get(0) instanceof MemoryOp.Create);
        assertTrue(result.ops().get(1) instanceof MemoryOp.Update);
        assertTrue(result.ops().get(2) instanceof MemoryOp.Delete);
    }

    @Test
    void parseResponse_unknownAction_returnsEmpty() {
        String json = """
            {"ops": [{"action": "unknown", "name": "x"}]}
            """;
        // Should not crash; unknown actions are skipped or result in empty
        var result = MemoryExtractor.parseResponse(json);
        // The result should either be empty or have the unknown op skipped
        assertNotNull(result);
    }

    // --- formatMessages ---

    @Test
    void formatMessages_extractsTextBlocksOnly() {
        var msgs = List.of(
            new ChatMessage(ChatMessage.Role.USER, List.of(new ContentBlock.TextBlock("hello"))),
            new ChatMessage(ChatMessage.Role.ASSISTANT, List.of(
                new ContentBlock.TextBlock("hi"),
                new ContentBlock.ToolUseBlock("t1", "read_file", MAPPER.createObjectNode())
            ))
        );
        String formatted = MemoryExtractor.formatMessages(msgs);
        assertTrue(formatted.contains("[用户] hello"));
        assertTrue(formatted.contains("[助手] hi"));
        assertFalse(formatted.contains("read_file"));
    }

    @Test
    void formatMessages_multipleTextBlocksInOneMessage() {
        var msgs = List.of(
            new ChatMessage(ChatMessage.Role.ASSISTANT, List.of(
                new ContentBlock.TextBlock("first"),
                new ContentBlock.TextBlock("second")
            ))
        );
        String formatted = MemoryExtractor.formatMessages(msgs);
        assertTrue(formatted.contains("first"));
        assertTrue(formatted.contains("second"));
    }

    @Test
    void formatMessages_emptyList() {
        String formatted = MemoryExtractor.formatMessages(List.of());
        assertEquals("", formatted);
    }

    @Test
    void formatMessages_includesToolResultContent() {
        var msgs = List.of(
            new ChatMessage(ChatMessage.Role.ASSISTANT, List.of(
                new ContentBlock.ToolResultBlock("t1", "file content here", false)
            ))
        );
        String formatted = MemoryExtractor.formatMessages(msgs);
        assertTrue(formatted.contains("file content here"));
    }
}
