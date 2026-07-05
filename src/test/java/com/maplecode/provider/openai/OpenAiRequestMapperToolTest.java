package com.maplecode.provider.openai;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.maplecode.provider.ChatMessage;
import com.maplecode.provider.ChatRequest;
import com.maplecode.provider.ContentBlock;
import com.maplecode.tool.Tool;
import com.maplecode.tool.ToolContext;
import com.maplecode.tool.ToolResult;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertTrue;

class OpenAiRequestMapperToolTest {

    private final OpenAiRequestMapper mapper = new OpenAiRequestMapper();
    private static final ObjectMapper JSON = new ObjectMapper();

    private static Tool mk(String name) {
        return new Tool() {
            @Override public String name() { return name; }
            @Override public String description() { return "desc-" + name; }
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
    void tools_field_wrapped_in_function_type() {
        var req = new ChatRequest("m", List.of(),
            List.of(new ChatMessage(ChatMessage.Role.USER,
                List.of(new ContentBlock.TextBlock("hi")))),
            null, List.of(mk("read_file")));
        String body = mapper.toJsonBody(req);
        assertTrue(body.contains("\"type\":\"function\""), body);
        assertTrue(body.contains("\"function\":{"), body);
        assertTrue(body.contains("\"name\":\"read_file\""), body);
        assertTrue(body.contains("\"description\":\"desc-read_file\""), body);
        assertTrue(body.contains("\"parameters\":{"), body);
    }

    @Test
    void assistant_message_with_tool_calls_emits_tool_calls_array() {
        var args = JSON.createObjectNode().put("path", "/tmp/x");
        var req = new ChatRequest("m", List.of(),
            List.of(new ChatMessage(ChatMessage.Role.ASSISTANT,
                List.of(
                    new ContentBlock.TextBlock("I'll read it"),
                    new ContentBlock.ToolUseBlock("call_1", "read_file", args)
                ))),
            null, null);
        String body = mapper.toJsonBody(req);
        assertTrue(body.contains("\"role\":\"assistant\""), body);
        assertTrue(body.contains("\"tool_calls\":["), body);
        assertTrue(body.contains("\"id\":\"call_1\""), body);
        assertTrue(body.contains("\"type\":\"function\""), body);
        assertTrue(body.contains("\"name\":\"read_file\""), body);
        assertTrue(body.contains("arguments"), body);
        assertTrue(body.contains("path"), body);
    }

    @Test
    void tool_result_becomes_role_tool_message() {
        var req = new ChatRequest("m", List.of(),
            List.of(new ChatMessage(ChatMessage.Role.USER,
                List.of(new ContentBlock.ToolResultBlock("call_1", "file contents", false)))),
            null, null);
        String body = mapper.toJsonBody(req);
        assertTrue(body.contains("\"role\":\"tool\""), body);
        assertTrue(body.contains("\"content\":\"file contents\""), body);
        assertTrue(body.contains("\"tool_call_id\":\"call_1\""), body);
    }
}
