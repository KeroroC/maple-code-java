package com.maplecode.provider.anthropic;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.maplecode.provider.ChatRequest;
import com.maplecode.provider.ContentBlock;
import com.maplecode.tool.Tool;
import com.maplecode.tool.ToolContext;
import com.maplecode.tool.ToolResult;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertTrue;

class AnthropicRequestMapperToolTest {

    private final AnthropicRequestMapper mapper = new AnthropicRequestMapper();
    private static final ObjectMapper JSON = new ObjectMapper();

    private static Tool mk(String name) {
        return new Tool() {
            @Override public String name() { return name; }
            @Override public String description() { return "desc of " + name; }
            @Override public JsonNode inputSchema() {
                var s = JSON.createObjectNode();
                s.put("type", "object");
                s.putObject("properties").putObject("path").put("type", "string");
                return s;
            }
            @Override public ToolResult execute(JsonNode args, ToolContext ctx) { return ToolResult.ok(""); }
        };
    }

    @Test
    void tools_field_emitted_when_provided() {
        var req = new ChatRequest("m", List.of(),
            List.of(new com.maplecode.provider.ChatMessage(
                com.maplecode.provider.ChatMessage.Role.USER,
                List.of(new ContentBlock.TextBlock("hi")))),
            null,
            List.of(mk("read_file")));
        String body = mapper.toJsonBody(req);
        assertTrue(body.contains("\"tools\":["), body);
        assertTrue(body.contains("\"name\":\"read_file\""), body);
        assertTrue(body.contains("\"description\":\"desc of read_file\""), body);
        assertTrue(body.contains("\"input_schema\":{"), body);
    }

    @Test
    void tool_use_message_wire_format() {
        var args = JSON.createObjectNode().put("path", "/tmp/x");
        var req = new ChatRequest("m", List.of(),
            List.of(new com.maplecode.provider.ChatMessage(
                com.maplecode.provider.ChatMessage.Role.ASSISTANT,
                List.of(
                    new ContentBlock.TextBlock("I'll read it"),
                    new ContentBlock.ToolUseBlock("tu_1", "read_file", args)
                ))),
            null, null);
        String body = mapper.toJsonBody(req);
        assertTrue(body.contains("\"role\":\"assistant\""), body);
        assertTrue(body.contains("\"type\":\"text\""), body);
        assertTrue(body.contains("\"text\":\"I'll read it\""), body);
        assertTrue(body.contains("\"type\":\"tool_use\""), body);
        assertTrue(body.contains("\"id\":\"tu_1\""), body);
        assertTrue(body.contains("\"name\":\"read_file\""), body);
        assertTrue(body.contains("\"input\":{\"path\":\"/tmp/x\"}"), body);
    }

    @Test
    void tool_result_message_wire_format() {
        var req = new ChatRequest("m", List.of(),
            List.of(new com.maplecode.provider.ChatMessage(
                com.maplecode.provider.ChatMessage.Role.USER,
                List.of(new ContentBlock.ToolResultBlock("tu_1", "file contents", false)))),
            null, null);
        String body = mapper.toJsonBody(req);
        assertTrue(body.contains("\"role\":\"user\""), body);
        assertTrue(body.contains("\"type\":\"tool_result\""), body);
        assertTrue(body.contains("\"tool_use_id\":\"tu_1\""), body);
        assertTrue(body.contains("\"content\":\"file contents\""), body);
        assertTrue(body.contains("\"is_error\":false"), body);
    }
}
