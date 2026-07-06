package com.maplecode.permission;

import org.junit.jupiter.api.Test;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

class PermissionContextTest {

    @Test
    void exposes_mode() {
        var ctx = new PermissionContext(PermissionMode.STRICT);
        assertEquals(PermissionMode.STRICT, ctx.mode());
    }

    @Test
    void exposes_session_sets() {
        Set<ToolCall> allow = ConcurrentHashMap.newKeySet();
        Set<ToolCall> deny = ConcurrentHashMap.newKeySet();
        allow.add(new ToolCall("exec", "ls"));
        var ctx = new PermissionContext(PermissionMode.DEFAULT, allow, deny);
        assertTrue(ctx.sessionAllows().contains(new ToolCall("exec", "ls")));
        assertTrue(ctx.sessionDenies().isEmpty());
    }

    @Test
    void session_sets_are_thread_safe_under_concurrent_add() throws Exception {
        Set<ToolCall> allow = ConcurrentHashMap.newKeySet();
        Set<ToolCall> deny = ConcurrentHashMap.newKeySet();
        var ctx = new PermissionContext(PermissionMode.DEFAULT, allow, deny);

        int threads = 100, perThread = 100;
        var pool = java.util.concurrent.Executors.newFixedThreadPool(16);
        var latch = new CountDownLatch(1);
        for (int t = 0; t < threads; t++) {
            final int tid = t;
            pool.submit(() -> {
                try { latch.await(); } catch (InterruptedException ignored) {}
                for (int i = 0; i < perThread; i++) {
                    ctx.sessionAllows().add(new ToolCall("exec", "cmd-" + tid + "-" + i));
                }
            });
        }
        latch.countDown();
        pool.shutdown();
        assertTrue(pool.awaitTermination(10, TimeUnit.SECONDS));
        assertEquals(threads * perThread, ctx.sessionAllows().size());
    }
}
