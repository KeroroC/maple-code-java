package com.maplecode.provider;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class TokenUsageTest {

    @Test
    void equalityFourFields() {
        assertEquals(new TokenUsage(10, 20, 0, 0), new TokenUsage(10, 20, 0, 0));
        assertNotEquals(new TokenUsage(10, 20, 0, 0), new TokenUsage(10, 20, 100, 0));
    }

    @Test
    void ofFactory() {
        var u = TokenUsage.of(50, 60);
        assertEquals(50, u.inputTokens());
        assertEquals(60, u.outputTokens());
        assertEquals(0, u.cacheCreationTokens());
        assertEquals(0, u.cacheReadTokens());
    }

    @Test
    void zeroUsage() {
        var u = new TokenUsage(0, 0, 0, 0);
        assertEquals(0, u.inputTokens());
        assertEquals(0, u.outputTokens());
    }
}
