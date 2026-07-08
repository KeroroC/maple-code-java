package com.maplecode.memory;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 熔断器：连续失败达到阈值后打开（isOpen=false），阻止后续提取请求。
 * 成功时重置计数器。
 */
public final class MemoryFailureCounter {

    private static final int THRESHOLD = 3;
    private final AtomicInteger failures = new AtomicInteger();
    private final AtomicBoolean tripped = new AtomicBoolean();

    public void recordFailure() {
        int n = failures.incrementAndGet();
        if (n >= THRESHOLD) tripped.set(true);
    }

    public void recordSuccess() {
        failures.set(0);
        tripped.set(false);
    }

    /** true = 熔断器关闭（正常工作），false = 熔断器打开（跳过提取）。 */
    public boolean isOpen() {
        return !tripped.get();
    }

    public int failures() {
        return failures.get();
    }
}
