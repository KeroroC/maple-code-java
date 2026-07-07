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
    PermissionMode permissionMode,
    AgentLimits agentLimits,
    McpConfig mcpConfig,                // nullable；null 表示未配置 mcp_servers 块
    int contextWindow,                  // 0 = not configured
    String summarizerModel              // null = not configured
) {

    public AppConfig {
        if (contextWindow < 0) throw new IllegalArgumentException("context_window must be >= 0");
    }

    public record Timeouts(int connectSeconds, int readSeconds) {
        public Duration connectDuration() { return Duration.ofSeconds(connectSeconds); }
        public Duration readDuration() { return Duration.ofSeconds(readSeconds); }
    }

    public record AgentLimits(int maxIterations, int maxConsecutiveUnknown) {
        public static final int DEFAULT_MAX_ITERATIONS = 50;
        public static final int DEFAULT_MAX_CONSECUTIVE_UNKNOWN = 3;

        public AgentLimits {
            if (maxIterations < 1) throw new IllegalArgumentException("max_iterations must be >= 1");
            if (maxConsecutiveUnknown < 1) throw new IllegalArgumentException("max_consecutive_unknown must be >= 1");
        }

        public static AgentLimits defaults() {
            return new AgentLimits(DEFAULT_MAX_ITERATIONS, DEFAULT_MAX_CONSECUTIVE_UNKNOWN);
        }
    }

    /** MCP 配置块，对应 YAML mcp_servers。 */
    public record McpConfig(boolean enabled, int startupTimeoutMs) {
        public static final int DEFAULT_STARTUP_TIMEOUT_MS = 5000;

        public McpConfig {
            if (startupTimeoutMs < 0) throw new IllegalArgumentException("startup_timeout_ms must be >= 0");
        }

        public static McpConfig defaults() {
            return new McpConfig(true, DEFAULT_STARTUP_TIMEOUT_MS);
        }

        public Duration startupTimeoutDuration() {
            return Duration.ofMillis(startupTimeoutMs);
        }
    }
}
