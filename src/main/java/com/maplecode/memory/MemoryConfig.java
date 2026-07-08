package com.maplecode.memory;

import com.maplecode.config.AppConfig;

public record MemoryConfig(
    boolean enabled,
    String memoryModel,       // null = 复用主模型
    int maxContextMessages    // 默认 10
) {
    public static final int DEFAULT_MAX_CONTEXT_MESSAGES = 10;

    public MemoryConfig {
        if (maxContextMessages < 1) {
            throw new IllegalArgumentException("max_context_messages must be >= 1");
        }
    }

    /** 禁用时的默认实例。 */
    public static MemoryConfig disabled() {
        return new MemoryConfig(false, null, DEFAULT_MAX_CONTEXT_MESSAGES);
    }

    public static MemoryConfig fromAppConfig(AppConfig config) {
        MemoryConfig mc = config.memoryConfig();
        return mc != null ? mc : disabled();
    }
}
