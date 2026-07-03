package com.maplecode.provider;

import com.maplecode.tool.Tool;

import java.util.List;

public record ChatRequest(
    String model,
    String systemPrompt,      // nullable
    List<ChatMessage> messages,
    ThinkingConfig thinking,  // nullable
    List<Tool> tools          // nullable；v1 旧测试传 null 也能跑
) {}
