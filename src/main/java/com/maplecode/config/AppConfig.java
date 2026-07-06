package com.maplecode.config;

import com.maplecode.permission.PermissionMode;
import com.maplecode.prompt.SystemBlock;
import com.maplecode.provider.ThinkingConfig;

import java.time.Duration;
import java.util.List;

public record AppConfig(
    String protocol,
    String model,
    String baseUrl,
    String apiKey,
    String yamlPrompt,                  // 来自 YAML `system_prompt`；nullable
    List<SystemBlock> systemBlocks,     // 默认 List.of()；App.main 启动时组装
    ThinkingConfig thinking,            // nullable
    Timeouts timeouts,
    PermissionMode permissionMode
) {
    public record Timeouts(int connectSeconds, int readSeconds) {
        public Duration connectDuration() { return Duration.ofSeconds(connectSeconds); }
        public Duration readDuration() { return Duration.ofSeconds(readSeconds); }
    }
}
