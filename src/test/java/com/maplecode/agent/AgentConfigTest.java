package com.maplecode.agent;

import com.maplecode.config.AppConfig;
import com.maplecode.error.ConfigException;
import com.maplecode.prompt.PlanModeReminder;
import com.maplecode.provider.ThinkingConfig;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class AgentConfigTest {

    @Test
    void defaultsAreSensible() {
        var c = AgentConfig.defaults();
        assertEquals("test-model", c.model());
        assertTrue(c.systemBlocks().isEmpty());
        assertNull(c.thinking());
        assertEquals(25, c.maxIterations());
        assertEquals(3, c.maxConsecutiveUnknown());
        assertEquals(PlanMode.NORMAL, c.planMode());
        assertEquals(PlanModeReminder.State.initial(), c.reminderState());
    }

    @Test
    void rejectsZeroIterations() {
        assertThrows(ConfigException.class,
            () -> new AgentConfig("m", List.of(), null, 0, 3,
                PlanMode.NORMAL, PlanModeReminder.State.initial()));
    }

    @Test
    void rejectsZeroConsecutiveUnknown() {
        assertThrows(ConfigException.class,
            () -> new AgentConfig("m", List.of(), null, 25, 0,
                PlanMode.NORMAL, PlanModeReminder.State.initial()));
    }

    @Test
    void planModeEnumHasTwoValues() {
        assertEquals(2, PlanMode.values().length);
    }

    @Test
    void fromAppConfigCopiesFields() {
        var thinking = new ThinkingConfig(ThinkingConfig.Type.ADAPTIVE, null, ThinkingConfig.Effort.MEDIUM);
        var app = new AppConfig("anthropic", "claude-sonnet-4-6", "https://api.anthropic.com",
            "sk-test", "You are helpful", List.of(), thinking, new AppConfig.Timeouts(10, 60),
            com.maplecode.permission.PermissionMode.DEFAULT);
        var agent = AgentConfig.fromAppConfig(app);

        assertEquals("claude-sonnet-4-6", agent.model());
        assertTrue(agent.systemBlocks().isEmpty());
        assertSame(thinking, agent.thinking());
        assertEquals(25, agent.maxIterations());
        assertEquals(3, agent.maxConsecutiveUnknown());
        assertEquals(PlanMode.NORMAL, agent.planMode());
    }

    @Test
    void fromAppConfigHandlesNullOptionals() {
        var app = new AppConfig("anthropic", "claude-sonnet-4-6", "https://api.anthropic.com",
            "sk-test", null, List.of(), null, new AppConfig.Timeouts(10, 60),
            com.maplecode.permission.PermissionMode.DEFAULT);
        var agent = AgentConfig.fromAppConfig(app);

        assertTrue(agent.systemBlocks().isEmpty());
        assertNull(agent.thinking());
    }

    @Test
    void withReminderStateCreatesCopy() {
        var original = AgentConfig.defaults();
        var newState = PlanModeReminder.State.initial().afterFull(1);
        var updated = original.withReminderState(newState);

        assertEquals(PlanModeReminder.State.initial(), original.reminderState());
        assertEquals(newState, updated.reminderState());
        assertEquals(original.model(), updated.model());
        assertEquals(original.systemBlocks(), updated.systemBlocks());
    }

    @Test
    void withPlanModeCreatesCopy() {
        var original = AgentConfig.defaults();
        var updated = original.withPlanMode(PlanMode.PLAN);

        assertEquals(PlanMode.NORMAL, original.planMode());
        assertEquals(PlanMode.PLAN, updated.planMode());
        assertEquals(original.model(), updated.model());
    }
}
