package com.maplecode.memory;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class MemoryFailureCounterTest {

    @Test
    void startsOpen() {
        var c = new MemoryFailureCounter();
        assertTrue(c.isOpen());
        assertEquals(0, c.failures());
    }

    @Test
    void threeFailuresTrips() {
        var c = new MemoryFailureCounter();
        c.recordFailure();
        c.recordFailure();
        assertTrue(c.isOpen());
        c.recordFailure();
        assertFalse(c.isOpen());
    }

    @Test
    void successResetsCounter() {
        var c = new MemoryFailureCounter();
        c.recordFailure();
        c.recordFailure();
        c.recordSuccess();
        assertTrue(c.isOpen());
        assertEquals(0, c.failures());
    }
}
