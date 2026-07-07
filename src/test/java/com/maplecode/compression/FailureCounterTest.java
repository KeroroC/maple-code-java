package com.maplecode.compression;

import org.junit.jupiter.api.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.*;

class FailureCounterTest {

    @Test
    void initialStateIsNotTripped() {
        var c = new FailureCounter(3);
        assertFalse(c.isTripped());
        assertEquals(0, c.failures());
    }

    @Test
    void threeFailuresTrip() {
        var c = new FailureCounter(3);
        c.recordFailure();
        c.recordFailure();
        assertFalse(c.isTripped());
        c.recordFailure();
        assertTrue(c.isTripped());
    }

    @Test
    void recordSuccessResetsCounter() {
        var c = new FailureCounter(3);
        c.recordFailure();
        c.recordFailure();
        c.recordSuccess();
        assertFalse(c.isTripped());
        assertEquals(0, c.failures());
        c.recordFailure();
        c.recordFailure();
        assertFalse(c.isTripped());  // reset后要重新累积
    }

    @Test
    void resetClearsEverything() {
        var c = new FailureCounter(3);
        c.recordFailure();
        c.recordFailure();
        c.recordFailure();
        assertTrue(c.isTripped());
        c.reset();
        assertFalse(c.isTripped());
        assertEquals(0, c.failures());
    }

    @Test
    void concurrentRecordFailureStaysAtomic() throws Exception {
        var c = new FailureCounter(1000);
        var pool = Executors.newFixedThreadPool(8);
        var latch = new CountDownLatch(1);
        var futures = IntStream.range(0, 100).mapToObj(i -> pool.submit(() -> {
            try {
                latch.await();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            c.recordFailure();
        })).toList();
        latch.countDown();
        for (var f : futures) f.get(5, TimeUnit.SECONDS);
        pool.shutdown();
        assertEquals(100, c.failures());
    }
}
