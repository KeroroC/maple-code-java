package com.maplecode.agent;

import com.maplecode.error.ConfigException;

public record AgentConfig(
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
        return new AgentConfig(25, 3, PlanMode.NORMAL);
    }
}

enum PlanMode { NORMAL, PLAN }
