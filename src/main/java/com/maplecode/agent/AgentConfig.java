package com.maplecode.agent;

import com.maplecode.config.AppConfig;
import com.maplecode.error.ConfigException;
import com.maplecode.provider.ThinkingConfig;

public record AgentConfig(
    String model,
    String systemPrompt,
    ThinkingConfig thinking,
    int maxIterations,
    int maxConsecutiveUnknown,
    PlanMode planMode
) {
    public AgentConfig {
        if (maxIterations < 1) {
            throw new ConfigException("maxIterations must be >= 1");
        }
        if (maxConsecutiveUnknown < 1) {
            throw new ConfigException("maxConsecutiveUnknown must be >= 1");
        }
    }

    public static AgentConfig defaults() {
        return new AgentConfig("test-model", null, null, 25, 3, PlanMode.NORMAL);
    }

    public static AgentConfig fromAppConfig(AppConfig app) {
        return new AgentConfig(app.model(), app.systemPrompt(), app.thinking(),
            25, 3, PlanMode.NORMAL);
    }
}
