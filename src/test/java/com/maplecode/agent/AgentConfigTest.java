package com.maplecode.agent;

import com.maplecode.config.AppConfig;
import com.maplecode.error.ConfigException;
import com.maplecode.provider.ThinkingConfig;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class AgentConfigTest {

    @Test
    void defaultsAreSensible() {
        var c = AgentConfig.defaults();
        assertEquals("test-model", c.model());
        assertNull(c.systemPrompt());
        assertNull(c.thinking());
        assertEquals(25, c.maxIterations());
        assertEquals(3, c.maxConsecutiveUnknown());
        assertEquals(PlanMode.NORMAL, c.planMode());
    }

    @Test
    void rejectsZeroIterations() {
        assertThrows(ConfigException.class,
            () -> new AgentConfig("m", null, null, 0, 3, PlanMode.NORMAL));
    }

    @Test
    void rejectsZeroConsecutiveUnknown() {
        assertThrows(ConfigException.class,
            () -> new AgentConfig("m", null, null, 25, 0, PlanMode.NORMAL));
    }

    @Test
    void planModeEnumHasTwoValues() {
        assertEquals(2, PlanMode.values().length);
    }

    @Test
    void fromAppConfigCopiesFields() {
        var thinking = new ThinkingConfig(ThinkingConfig.Type.ADAPTIVE, null, ThinkingConfig.Effort.MEDIUM);
        var app = new AppConfig("anthropic", "claude-sonnet-4-6", "https://api.anthropic.com",
            "sk-test", "You are helpful", thinking, new AppConfig.Timeouts(10, 60));
        var agent = AgentConfig.fromAppConfig(app);

        assertEquals("claude-sonnet-4-6", agent.model());
        assertEquals("You are helpful", agent.systemPrompt());
        assertSame(thinking, agent.thinking());
        assertEquals(25, agent.maxIterations());
        assertEquals(3, agent.maxConsecutiveUnknown());
        assertEquals(PlanMode.NORMAL, agent.planMode());
    }

    @Test
    void fromAppConfigHandlesNullOptionals() {
        var app = new AppConfig("anthropic", "claude-sonnet-4-6", "https://api.anthropic.com",
            "sk-test", null, null, new AppConfig.Timeouts(10, 60));
        var agent = AgentConfig.fromAppConfig(app);

        assertNull(agent.systemPrompt());
        assertNull(agent.thinking());
    }
}
