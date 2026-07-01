package com.maplecode.provider;

import java.util.List;

public record ChatRequest(
    String model,
    String systemPrompt,      // nullable
    List<ChatMessage> messages,
    ThinkingConfig thinking   // nullable
) {}