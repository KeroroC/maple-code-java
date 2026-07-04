package com.maplecode.provider;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class TokenUsageTest {

    @Test
    void equality() {
        assertEquals(new TokenUsage(10, 20), new TokenUsage(10, 20));
        assertNotEquals(new TokenUsage(10, 20), new TokenUsage(10, 21));
    }

    @Test
    void zeroUsage() {
        var u = new TokenUsage(0, 0);
        assertEquals(0, u.inputTokens());
        assertEquals(0, u.outputTokens());
    }
}
