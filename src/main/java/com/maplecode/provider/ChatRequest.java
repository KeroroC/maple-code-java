package com.maplecode.provider;

import com.maplecode.prompt.SystemBlock;
import com.maplecode.tool.Tool;

import java.util.List;

public record ChatRequest(
    String model,
    List<SystemBlock> systemBlocks,        // 替代旧 systemPrompt: String
    List<ChatMessage> messages,
    ThinkingConfig thinking,               // nullable
    List<Tool> tools                       // nullable；null = no tools available, mappers coerce to empty
) {}
