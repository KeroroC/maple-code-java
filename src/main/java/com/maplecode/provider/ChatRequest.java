package com.maplecode.provider;

import com.maplecode.prompt.SystemBlock;
import com.maplecode.tool.Tool;

import java.util.List;

public record ChatRequest(
    String model,
    List<SystemBlock> systemBlocks,        // 替代旧 systemPrompt: String
    List<ChatMessage> messages,
    ThinkingConfig thinking,               // nullable
    List<Tool> tools,                      // nullable；null = no tools available, mappers coerce to empty
    List<ContentBlock> transientReminder   // 新增；用于运行时 reminder 注入
) {
    /** 向后兼容 5 参重载：transientReminder 默认为空 list。 */
    public ChatRequest(String model, List<SystemBlock> systemBlocks,
                       List<ChatMessage> messages, ThinkingConfig thinking,
                       List<Tool> tools) {
        this(model, systemBlocks, messages, thinking, tools, List.of());
    }
}
