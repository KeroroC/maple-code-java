package com.maplecode.compression;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

public final class FailureCounter {
    private final int threshold;
    private final AtomicInteger failures = new AtomicInteger();
    private final AtomicBoolean tripped = new AtomicBoolean();

    public FailureCounter(int threshold) {
        if (threshold < 1) throw new IllegalArgumentException("threshold must be >= 1");
        this.threshold = threshold;
    }

    public void recordFailure() {
        int n = failures.incrementAndGet();
        if (n >= threshold) tripped.set(true);
    }

    public void recordSuccess() {
        failures.set(0);
    }

    public void reset() {
        failures.set(0);
        tripped.set(false);
    }

    public int failures() {
        return failures.get();
    }

    public boolean isTripped() {
        return tripped.get();
    }
}
