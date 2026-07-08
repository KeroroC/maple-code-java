package com.maplecode.memory;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.maplecode.provider.*;
import com.maplecode.provider.ContentBlock.TextBlock;
import com.maplecode.provider.ContentBlock.ToolResultBlock;
import com.maplecode.prompt.SystemBlock;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 调用 LLM 从近期对话中提取记忆操作（create/update/delete），
 * 并解析 LLM 返回的 JSON 响应。JSON 解析对 markdown 代码块包裹有容错。
 */
public final class MemoryExtractor {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** 匹配 ``` 或 ```json 开头的代码块标记 */
    private static final Pattern CODE_BLOCK_START = Pattern.compile("^```(?:json)?\\s*\\n?", Pattern.MULTILINE);
    /** 匹配结尾的 ``` 标记 */
    private static final Pattern CODE_BLOCK_END = Pattern.compile("\\n?```\\s*$");
    /** 贪婪匹配最外层 JSON 对象 */
    private static final Pattern JSON_OBJECT = Pattern.compile("\\{[\\s\\S]*\\}");

    private static final String EXTRACTION_SYSTEM_PROMPT = """
        You are a memory extraction agent. Your job is to analyze a conversation
        and extract important facts, preferences, or references that should be
        remembered for future sessions.

        CATEGORIES:
        - user: Personal preferences, habits, or settings of the user (e.g., preferred language, coding style)
        - feedback: Corrections or feedback the user gave about your behavior (e.g., "don't use emojis")
        - project: Project-specific facts (e.g., "uses Spring Boot 3", "deployed on AWS")
        - reference: External references, URLs, documentation links mentioned

        RULES:
        - Only extract facts that are explicitly stated or clearly implied.
        - Do NOT extract transient conversation state or one-time actions.
        - Use short, descriptive "name" values (lowercase, hyphens, max 50 chars).
        - "content" should be a concise summary (1-2 sentences).
        - If nothing is worth remembering, return {"ops": []}.

        OUTPUT FORMAT (strict JSON, no markdown, no prose):
        {
          "ops": [
            {"action": "create", "category": "<category>", "name": "<name>", "content": "<content>"},
            {"action": "update", "name": "<name>", "content": "<content>"},
            {"action": "delete", "name": "<name>"}
          ]
        }

        "category" is required for "create" and must be one of: user, feedback, project, reference.
        Only output the JSON object. No explanation, no markdown fencing.
        """;

    private final LlmProvider provider;
    private final String model;

    public MemoryExtractor(LlmProvider provider, String model) {
        this.provider = provider;
        this.model = model;
    }

    /**
     * 调用 LLM 提取记忆操作。
     *
     * @param recentMessages 近期对话消息
     * @return 提取到的记忆操作列表；LLM 返回异常时返回 empty
     */
    public MemoryOpsResult extract(List<ChatMessage> recentMessages) {
        if (recentMessages.isEmpty()) {
            return MemoryOpsResult.empty();
        }

        String formatted = formatMessages(recentMessages);
        if (formatted.isBlank()) {
            return MemoryOpsResult.empty();
        }

        String raw = callLlm(formatted);
        return parseResponse(raw);
    }

    /**
     * 解析 LLM 返回的 JSON 字符串，容错处理 markdown 代码块包裹。
     * 包级别可见，便于单元测试直接调用。
     */
    static MemoryOpsResult parseResponse(String raw) {
        if (raw == null || raw.isBlank()) {
            return MemoryOpsResult.empty();
        }

        String json = stripCodeBlocks(raw.trim());
        return tryParse(json);
    }

    /**
     * 将消息格式化为 [用户]/[助手] 前缀的纯文本，仅包含文本内容。
     * 包级别可见，便于单元测试直接调用。
     */
    static String formatMessages(List<ChatMessage> messages) {
        StringBuilder sb = new StringBuilder();
        for (ChatMessage msg : messages) {
            String prefix = msg.role() == ChatMessage.Role.USER ? "[用户]" : "[助手]";
            for (ContentBlock block : msg.blocks()) {
                switch (block) {
                    case TextBlock tb -> sb.append(prefix).append(" ").append(tb.text()).append("\n");
                    case ToolResultBlock trb -> {
                        if (!trb.content().isBlank()) {
                            sb.append(prefix).append(" ").append(trb.content()).append("\n");
                        }
                    }
                    // ToolUseBlock 不包含在格式化输出中
                    default -> {}
                }
            }
        }
        return sb.toString();
    }

    // --- 内部方法 ---

    private String callLlm(String formattedMessages) {
        StringBuilder sb = new StringBuilder();

        ChatRequest request = new ChatRequest(
            model,
            List.of(new SystemBlock(EXTRACTION_SYSTEM_PROMPT, false, "memory-extractor")),
            List.of(new ChatMessage(ChatMessage.Role.USER, List.of(new TextBlock(formattedMessages)))),
            null,   // 不启用 thinking
            null    // 不使用工具
        );

        provider.stream(request, chunk -> {
            if (chunk instanceof StreamChunk.TextDelta td) {
                sb.append(td.text());
            } else if (chunk instanceof StreamChunk.Error e) {
                throw new MemoryExtractorException("Provider error: " + e.code() + " - " + e.message());
            }
        });

        return sb.toString();
    }

    private static String stripCodeBlocks(String text) {
        // 先尝试去掉 ```json ... ``` 包裹
        String stripped = CODE_BLOCK_START.matcher(text).replaceAll("");
        stripped = CODE_BLOCK_END.matcher(stripped).replaceAll("");
        return stripped.trim();
    }

    private static MemoryOpsResult tryParse(String json) {
        // 直接尝试解析
        MemoryOpsResult result = tryParseJson(json);
        if (result != null) {
            return result;
        }
        // 贪婪匹配 JSON 对象
        Matcher m = JSON_OBJECT.matcher(json);
        if (m.find()) {
            result = tryParseJson(m.group());
            if (result != null) {
                return result;
            }
        }
        return MemoryOpsResult.empty();
    }

    private static MemoryOpsResult tryParseJson(String json) {
        try {
            JsonNode root = MAPPER.readTree(json);
            JsonNode opsNode = root.get("ops");
            if (opsNode == null || !opsNode.isArray()) {
                return MemoryOpsResult.empty();
            }
            List<MemoryOp> ops = new ArrayList<>();
            for (JsonNode opNode : opsNode) {
                parseOp(opNode).ifPresent(ops::add);
            }
            return new MemoryOpsResult(ops);
        } catch (Exception e) {
            return null; // 解析失败，返回 null 让调用方尝试其他策略
        }
    }

    private static java.util.Optional<MemoryOp> parseOp(JsonNode opNode) {
        String action = textOrNull(opNode, "action");
        if (action == null) {
            return java.util.Optional.empty();
        }
        return switch (action) {
            case "create" -> {
                String categoryStr = textOrNull(opNode, "category");
                String name = textOrNull(opNode, "name");
                String content = textOrNull(opNode, "content");
                if (categoryStr == null || name == null || content == null) {
                    yield java.util.Optional.empty();
                }
                try {
                    MemoryCategory category = MemoryCategory.fromDirName(categoryStr);
                    yield java.util.Optional.of(new MemoryOp.Create(category, name, content));
                } catch (IllegalArgumentException e) {
                    yield java.util.Optional.empty();
                }
            }
            case "update" -> {
                String name = textOrNull(opNode, "name");
                String content = textOrNull(opNode, "content");
                if (name == null || content == null) {
                    yield java.util.Optional.empty();
                }
                yield java.util.Optional.of(new MemoryOp.Update(name, content));
            }
            case "delete" -> {
                String name = textOrNull(opNode, "name");
                if (name == null) {
                    yield java.util.Optional.empty();
                }
                yield java.util.Optional.of(new MemoryOp.Delete(name));
            }
            default -> java.util.Optional.empty();
        };
    }

    private static String textOrNull(JsonNode node, String field) {
        JsonNode child = node.get(field);
        if (child == null || !child.isTextual()) {
            return null;
        }
        String value = child.asText();
        return value.isEmpty() ? null : value;
    }
}
