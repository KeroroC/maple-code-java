package com.maplecode.agent;

import com.maplecode.config.AppConfig;
import com.maplecode.error.ConfigException;
import com.maplecode.prompt.PlanModeReminder;
import com.maplecode.prompt.SystemBlock;
import com.maplecode.provider.ThinkingConfig;

import java.util.List;

public record AgentConfig(
    String model,
    List<SystemBlock> systemBlocks,
    ThinkingConfig thinking,
    int maxIterations,
    int maxConsecutiveUnknown,
    PlanMode planMode,
    PlanModeReminder.State reminderState
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
        return new AgentConfig("test-model", List.of(), null, 25, 3,
            PlanMode.NORMAL, PlanModeReminder.State.initial());
    }

    public static AgentConfig fromAppConfig(AppConfig app) {
        return new AgentConfig(app.model(), List.of(), app.thinking(),
            25, 3, PlanMode.NORMAL, PlanModeReminder.State.initial());
    }

    public AgentConfig withReminderState(PlanModeReminder.State state) {
        return new AgentConfig(model, systemBlocks, thinking,
            maxIterations, maxConsecutiveUnknown, planMode, state);
    }

    public AgentConfig withPlanMode(PlanMode mode) {
        return new AgentConfig(model, systemBlocks, thinking,
            maxIterations, maxConsecutiveUnknown, mode, reminderState);
    }
}
