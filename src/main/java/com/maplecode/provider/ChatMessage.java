package com.maplecode.provider;

import java.util.List;

/**
 * ChatMessage 持有 ContentBlock 列表。
 * Anthropic 一条消息可以含 text + tool_use 等多 block；
 * OpenAI 一条 assistant 消息可能同时有 content + tool_calls。
 * v1 的 String content 不够用，改为 List<ContentBlock>。
 */
public record ChatMessage(Role role, List<ContentBlock> blocks) {
    public enum Role { USER, ASSISTANT }
}