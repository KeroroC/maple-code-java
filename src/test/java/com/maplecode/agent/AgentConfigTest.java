package com.maplecode.agent;

import com.maplecode.error.ConfigException;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class AgentConfigTest {

    @Test
    void defaultsAreSensible() {
        var c = AgentConfig.defaults();
        assertEquals(25, c.maxIterations());
        assertEquals(3, c.maxConsecutiveUnknown());
        assertEquals(PlanMode.NORMAL, c.planMode());
    }

    @Test
    void rejectsZeroIterations() {
        assertThrows(ConfigException.class,
            () -> new AgentConfig(0, 3, PlanMode.NORMAL));
    }

    @Test
    void rejectsZeroConsecutiveUnknown() {
        assertThrows(ConfigException.class,
            () -> new AgentConfig(25, 0, PlanMode.NORMAL));
    }

    @Test
    void planModeEnumHasTwoValues() {
        assertEquals(2, PlanMode.values().length);
    }
}
