package com.maplecode.provider;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * 流式响应中所有可能的事件类型。
 *
 * sealed interface 是 Java 17 引入的特性，通过 permits 关键字
 * 明确列出所有允许实现该接口的子类，编译器据此保证：
 * 1. 类型层次是封闭的——不允许其他类意外实现该接口；
 * 2. switch 表达式可以做到穷尽检查——新增子类时未处理的 switch 会编译报错。
 */
public sealed interface StreamChunk
    permits StreamChunk.TextDelta,
            StreamChunk.ThinkingDelta,
            StreamChunk.MessageStart,
            StreamChunk.MessageEnd,
            StreamChunk.Error,
            StreamChunk.ToolUseStart,
            StreamChunk.ToolUseDelta,
            StreamChunk.ToolUseEnd {

    record TextDelta(String text) implements StreamChunk {}
    record ThinkingDelta(String text) implements StreamChunk {}
    record MessageStart() implements StreamChunk {}
    record MessageEnd(StopReason reason, TokenUsage usage) implements StreamChunk {}
    record Error(String code, String message) implements StreamChunk {}

    /**
     * 流式 tool_use 的三段拼装：
     *   ToolUseStart  → 工具被声明（id + name）
     *   ToolUseDelta  → 参数 JSON 碎片（partialJson）
     *   ToolUseEnd    → 工具参数完整，input 是解析后的 JsonNode
     *
     * Anthropic 的 content_block_start/delta/stop 拆成这三段。
     * OpenAI 的 delta.tool_calls 同构映射（按 tool_calls 数组 index 跟踪）。
     */
    record ToolUseStart(String id, String name) implements StreamChunk {}
    record ToolUseDelta(String id, String partialJson) implements StreamChunk {}
    record ToolUseEnd(String id, String name, JsonNode input) implements StreamChunk {}

    enum StopReason {
        END_TURN, MAX_TOKENS, STOP, ERROR, TOOL_USE,         // v2 已有
        MAX_ITERATIONS,                                       // v3 新增
        CONSECUTIVE_UNKNOWN,                                  // v3 新增
        PROVIDER_ERROR,                                       // v3 新增
        USER_CANCELLED                                        // v3 新增
    }
}
