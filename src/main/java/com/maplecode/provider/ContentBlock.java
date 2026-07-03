package com.maplecode.provider;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * 消息内容的原子单元。Anthropic 的消息是 content block 数组，
 * OpenAI 的 assistant 消息是 content + tool_calls，
 * 都用这个 sealed 层次统一。
 *
 * sealed 保证 switch 穷尽。
 */
public sealed interface ContentBlock
    permits ContentBlock.TextBlock,
            ContentBlock.ToolUseBlock,
            ContentBlock.ToolResultBlock {

    /** 文本段。Anthropic 走 {type:"text",text:..}，OpenAI 走 string content。 */
    record TextBlock(String text) implements ContentBlock {}

    /** 助手发起的工具调用。id 对应 Anthropic tool_use_id / OpenAI tool_call_id。 */
    record ToolUseBlock(String id, String name, JsonNode input) implements ContentBlock {}

    /** 工具结果回灌。content 是字符串（v2 简化；后续可扩结构化）。 */
    record ToolResultBlock(String toolUseId, String content, boolean isError) implements ContentBlock {}
}
