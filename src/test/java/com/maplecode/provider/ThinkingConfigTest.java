package com.maplecode.provider;

import com.maplecode.error.ConfigException;
import org.junit.jupiter.api.Test;

import static com.maplecode.provider.ThinkingConfig.Effort.HIGH;
import static com.maplecode.provider.ThinkingConfig.Type.ADAPTIVE;
import static com.maplecode.provider.ThinkingConfig.Type.ENABLED;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ThinkingConfigTest {

    @Test
    void adaptive_with_effort_constructs_ok() {
        ThinkingConfig tc = new ThinkingConfig(ADAPTIVE, null, HIGH);
        assertEquals(ADAPTIVE, tc.type());
        assertEquals(HIGH, tc.effort());
        assertNull(tc.budgetTokens());
    }

    @Test
    void enabled_with_budget_tokens_constructs_ok() {
        ThinkingConfig tc = new ThinkingConfig(ENABLED, 10000, null);
        assertEquals(ENABLED, tc.type());
        assertEquals(10000, tc.budgetTokens());
        assertNull(tc.effort());
    }

    @Test
    void enabled_with_budget_tokens_below_1024_throws() {
        ConfigException ex = assertThrows(ConfigException.class,
            () -> new ThinkingConfig(ENABLED, 1023, null));
        assertEquals("extended_thinking.type=enabled requires budget_tokens >= 1024", ex.getMessage());
    }

    @Test
    void enabled_without_budget_tokens_throws() {
        assertThrows(ConfigException.class,
            () -> new ThinkingConfig(ENABLED, null, null));
    }

    @Test
    void enabled_with_effort_throws_mutual_exclusion() {
        ConfigException ex = assertThrows(ConfigException.class,
            () -> new ThinkingConfig(ENABLED, 10000, HIGH));
        assertEquals("extended_thinking.type=enabled and effort are mutually exclusive", ex.getMessage());
    }

    @Test
    void adaptive_without_effort_throws() {
        assertThrows(ConfigException.class,
            () -> new ThinkingConfig(ADAPTIVE, null, null));
    }

    @Test
    void adaptive_with_budget_tokens_throws_mutual_exclusion() {
        ConfigException ex = assertThrows(ConfigException.class,
            () -> new ThinkingConfig(ADAPTIVE, 10000, HIGH));
        assertEquals("extended_thinking.type=adaptive and budget_tokens are mutually exclusive", ex.getMessage());
    }

    @Test
    void boundary_1024_is_accepted() {
        assertDoesNotThrow(() -> new ThinkingConfig(ENABLED, 1024, null));
    }
}