package com.maplecode.provider;

import com.maplecode.tool.Tool;

import java.util.List;

public record ChatRequest(
    String model,
    String systemPrompt,      // nullable
    List<ChatMessage> messages,
    ThinkingConfig thinking,  // nullable
    List<Tool> tools          // nullable；null = no tools available, mappers coerce to empty
) {}
