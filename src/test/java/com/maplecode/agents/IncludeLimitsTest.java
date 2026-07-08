package com.maplecode.agents;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class IncludeLimitsTest {

    @Test
    void defaultsMatchSpec() {
        IncludeLimits limits = IncludeLimits.defaults();
        assertEquals(3, limits.maxDepth());
        assertEquals(1_048_576, limits.maxFileSize());
        assertEquals(65_536, limits.maxTotalBytes());
    }

    @Test
    void recordAccessorsWork() {
        IncludeLimits limits = new IncludeLimits(5, 100, 200);
        assertEquals(5, limits.maxDepth());
        assertEquals(100, limits.maxFileSize());
        assertEquals(200, limits.maxTotalBytes());
    }
}
