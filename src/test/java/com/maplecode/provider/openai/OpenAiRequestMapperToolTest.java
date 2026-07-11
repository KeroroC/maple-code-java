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

import static org.junit.jupiter.api.Assertions.assertEquals;
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

    @Test
    void multiple_tool_results_become_separate_role_tool_messages() throws Exception {
        var req = new ChatRequest("m", List.of(),
            List.of(new ChatMessage(ChatMessage.Role.USER,
                List.of(
                    new ContentBlock.ToolResultBlock("call_1", "result_1", false),
                    new ContentBlock.ToolResultBlock("call_2", "result_2", true),
                    new ContentBlock.ToolResultBlock("call_3", "result_3", false)
                ))),
            null, null);
        JsonNode root = JSON.readTree(mapper.toJsonBody(req));
        var msgs = root.path("messages");

        // 三个 ToolResultBlock 应展开为三条 role=tool 消息
        assertEquals(3, msgs.size(), "three tool results must produce three messages");
        for (int i = 0; i < 3; i++) {
            assertEquals("tool", msgs.get(i).path("role").asText(),
                "message " + i + " must have role=tool");
        }
        assertEquals("call_1", msgs.get(0).path("tool_call_id").asText());
        assertEquals("result_1", msgs.get(0).path("content").asText());
        assertEquals("call_2", msgs.get(1).path("tool_call_id").asText());
        assertEquals("result_2", msgs.get(1).path("content").asText());
        assertEquals("call_3", msgs.get(2).path("tool_call_id").asText());
        assertEquals("result_3", msgs.get(2).path("content").asText());
    }

    @Test
    void mixed_text_and_tool_result_in_user_message() throws Exception {
        // 理论上 AgentLoop 不会产生这种消息，但 mapper 应该容错
        var req = new ChatRequest("m", List.of(),
            List.of(new ChatMessage(ChatMessage.Role.USER,
                List.of(
                    new ContentBlock.TextBlock("some text"),
                    new ContentBlock.ToolResultBlock("call_1", "result", false)
                ))),
            null, null);
        JsonNode root = JSON.readTree(mapper.toJsonBody(req));
        var msgs = root.path("messages");

        // 混合内容走普通 user 分支（只拼 TextBlock）
        assertEquals(1, msgs.size());
        assertEquals("user", msgs.get(0).path("role").asText());
        assertEquals("some text", msgs.get(0).path("content").asText());
    }
}
