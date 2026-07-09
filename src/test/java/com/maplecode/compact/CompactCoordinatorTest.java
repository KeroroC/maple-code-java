package com.maplecode.compact;

import com.maplecode.provider.*;
import com.maplecode.provider.ChatMessage.Role;
import com.maplecode.provider.ContentBlock.TextBlock;
import com.maplecode.session.ChatSession;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class CompactCoordinatorTest {

    private static List<ChatMessage> largeMessages() {
        // ~38,000 chars → ~9,500 tokens (exceeds threshold in small-window configs)
        return List.of(
            new ChatMessage(Role.USER, List.of(new TextBlock("x".repeat(38_000)))));
    }

    private static List<ChatMessage> smallMessages() {
        // ~2,000 chars → ~500 tokens
        return List.of(
            new ChatMessage(Role.USER, List.of(new TextBlock("x".repeat(2_000)))));
    }

    private static CompactConfig smallCfg() {
        // window=10,000, autoMargin=1,000 → auto threshold = 9,000 tokens
        return new CompactConfig(
            10_000, 1_000, 500,
            8_000, 30_000,
            5_000, 3,
            8, 4,
            3);
    }

    private ChatSession mockSession(List<ChatMessage> messages) {
        ChatSession session = mock(ChatSession.class);
        when(session.messages()).thenReturn(messages);
        return session;
    }

    private LlmProvider mockProvider() {
        return mock(LlmProvider.class);
    }

    // ---- Test 1: belowThresholdReturnsNoop ----

    @Test
    void belowThresholdReturnsNoop(@TempDir Path tmp) throws Exception {
        var cfg = smallCfg();
        var storage = new CompactStorage(tmp.resolve("s"));
        var counter = new FailureCounter(cfg.failureThreshold());
        var ctx = new CompactContext(cfg, storage, counter);

        var offloader = mock(Offloader.class);
        var summarizer = mock(ConversationSummarizer.class);
        var coordinator = new CompactCoordinator(ctx, mockProvider(), offloader, summarizer);

        var session = mockSession(smallMessages());
        var outcome = coordinator.beforeRequest(session, CompactTrigger.AUTO, null);

       assertInstanceOf(CompactResult.Noop.class, outcome.result());
        assertNull(outcome.newMessages(), "NOOP should have null newMessages");
        verifyNoInteractions(offloader, summarizer);
    }

    // ---- Test 2: offloadOnlyReturnsNewMessages ----

    @Test
    void offloadOnlyReturnsNewMessages(@TempDir Path tmp) throws Exception {
        var cfg = smallCfg();
        var storage = new CompactStorage(tmp.resolve("s"));
        var counter = new FailureCounter(cfg.failureThreshold());
        var ctx = new CompactContext(cfg, storage, counter);

        // Offload brings tokens under threshold: 8,000 chars → 2,000 tokens < 9,000
        List<ChatMessage> offloaded = smallMessages();
        var offloader = mock(Offloader.class);
        when(offloader.apply(any(), any())).thenReturn(offloaded);

        var summarizer = mock(ConversationSummarizer.class);
        var coordinator = new CompactCoordinator(ctx, mockProvider(), offloader, summarizer);

        var session = mockSession(largeMessages());
        var outcome = coordinator.beforeRequest(session, CompactTrigger.AUTO, null);

       assertInstanceOf(CompactResult.ChangedOffloadOnly.class, outcome.result());
        var result = (CompactResult.ChangedOffloadOnly) outcome.result();
        assertEquals(0, result.offloadedCount(), "Mock offloader returns 0 by default");
        assertNotNull(outcome.newMessages(), "Changed should have non-null newMessages");
        verify(offloader).apply(any(), any());
        verifyNoInteractions(summarizer);
    }

    // ---- Test 3: offloadInsufficientCallsSummarizer ----

    @Test
    void offloadInsufficientCallsSummarizer(@TempDir Path tmp) throws Exception {
        var cfg = smallCfg();
        var storage = new CompactStorage(tmp.resolve("s"));
        var counter = new FailureCounter(cfg.failureThreshold());
        var ctx = new CompactContext(cfg, storage, counter);

        // Offload returns messages still too large: 38,000 chars → 9,500 tokens > 9,000
        var offloader = mock(Offloader.class);
        when(offloader.apply(any(), any())).thenReturn(largeMessages());

        // Summarizer compresses to small
        var summarizer = mock(ConversationSummarizer.class);
        when(summarizer.apply(any(), any())).thenReturn(smallMessages());

        var coordinator = new CompactCoordinator(ctx, mockProvider(), offloader, summarizer);

        var session = mockSession(largeMessages());
        var outcome = coordinator.beforeRequest(session, CompactTrigger.AUTO, null);

        assertInstanceOf(CompactResult.ChangedFull.class, outcome.result());
        var result = (CompactResult.ChangedFull) outcome.result();
        assertEquals(0, result.offloadedCount(), "Mock offloader returns 0 by default");
        assertTrue(result.summaryInputTokens() > 0, "summaryInputTokens should be > 0");
        assertNotNull(outcome.newMessages());
        verify(offloader).apply(any(), any());
        verify(summarizer).apply(any(), any());
        assertEquals(0, counter.failures(), "Success should reset counter");
    }

    // ---- Test 4: summarizerExceptionIncrementsCounter ----

    @Test
    void summarizerExceptionIncrementsCounter(@TempDir Path tmp) throws Exception {
        var cfg = smallCfg();
        var storage = new CompactStorage(tmp.resolve("s"));
        var counter = new FailureCounter(cfg.failureThreshold());
        var ctx = new CompactContext(cfg, storage, counter);

        var offloader = mock(Offloader.class);
        when(offloader.apply(any(), any())).thenReturn(largeMessages());

        var summarizer = mock(ConversationSummarizer.class);
        when(summarizer.apply(any(), any()))
            .thenThrow(new CompactException("LLM refused"));

        var coordinator = new CompactCoordinator(ctx, mockProvider(), offloader, summarizer);

        var session = mockSession(largeMessages());
        var outcome = coordinator.beforeRequest(session, CompactTrigger.AUTO, null);

        assertInstanceOf(CompactResult.FailedSummary.class, outcome.result());
        var result = (CompactResult.FailedSummary) outcome.result();
        assertEquals(1, result.consecutiveFailures(), "Counter should be 1 after first failure");
        assertEquals(1, counter.failures(), "Counter state should be 1");
        assertTrue(result.reason().contains("LLM refused"), "Reason should contain exception message");
        assertNull(outcome.newMessages(), "Failed should have null newMessages");
    }

    // ---- Test 5: trippedCounterSkipsAuto ----

    @Test
    void trippedCounterSkipsAuto(@TempDir Path tmp) throws Exception {
        var cfg = smallCfg();
        var storage = new CompactStorage(tmp.resolve("s"));
        var counter = new FailureCounter(cfg.failureThreshold());
        var ctx = new CompactContext(cfg, storage, counter);

        // Trip the counter: 3 failures (threshold=3)
        counter.recordFailure();
        counter.recordFailure();
        counter.recordFailure();
        assertTrue(counter.isTripped());

        var offloader = mock(Offloader.class);
        var summarizer = mock(ConversationSummarizer.class);
        var coordinator = new CompactCoordinator(ctx, mockProvider(), offloader, summarizer);

        var session = mockSession(largeMessages());
        var outcome = coordinator.beforeRequest(session, CompactTrigger.AUTO, null);

        assertInstanceOf(CompactResult.SkippedCircuitOpen.class, outcome.result());
        var result = (CompactResult.SkippedCircuitOpen) outcome.result();
        assertEquals(3, result.consecutiveFailures());
        verifyNoInteractions(offloader, summarizer);
    }

    // ---- Test 6: trippedCounterAllowsManual ----

    @Test
    void trippedCounterAllowsManual(@TempDir Path tmp) throws Exception {
        var cfg = smallCfg();
        var storage = new CompactStorage(tmp.resolve("s"));
        var counter = new FailureCounter(cfg.failureThreshold());
        var ctx = new CompactContext(cfg, storage, counter);

        // Trip the counter
        counter.recordFailure();
        counter.recordFailure();
        counter.recordFailure();
        assertTrue(counter.isTripped());

        var offloader = mock(Offloader.class);
        when(offloader.apply(any(), any())).thenReturn(smallMessages());

        var summarizer = mock(ConversationSummarizer.class);
        var coordinator = new CompactCoordinator(ctx, mockProvider(), offloader, summarizer);

        var session = mockSession(largeMessages());
        var outcome = coordinator.beforeRequest(session, CompactTrigger.MANUAL, null);

        // Should NOT be SkippedCircuitOpen — manual bypasses circuit breaker
        assertInstanceOf(CompactResult.ChangedOffloadOnly.class, outcome.result());
        verify(offloader).apply(any(), any());
    }

    // ---- Test 7: recordUsageUpdatesLastSeen ----

    @Test
    void recordUsageUpdatesLastSeen(@TempDir Path tmp) throws Exception {
        var cfg = smallCfg();
        var storage = new CompactStorage(tmp.resolve("s"));
        var counter = new FailureCounter(cfg.failureThreshold());
        var ctx = new CompactContext(cfg, storage, counter);

        var coordinator = new CompactCoordinator(ctx, mockProvider(),
            mock(Offloader.class), mock(ConversationSummarizer.class));

        assertNull(coordinator.lastSeenUsage(), "Initial lastSeenUsage should be null");

        var usage = new TokenUsage(1000, 500, 100, 200);
        coordinator.recordUsage(usage);

        assertSame(usage, coordinator.lastSeenUsage(), "lastSeenUsage should return recorded usage");

        var usage2 = new TokenUsage(2000, 800, 0, 0);
        coordinator.recordUsage(usage2);

        assertSame(usage2, coordinator.lastSeenUsage(), "lastSeenUsage should update to latest");
    }

    // ---- Test 9: offloaderExceptionReturnsFailedOffload ----

    @Test
    void offloaderExceptionReturnsFailedOffload(@TempDir Path tmp) throws Exception {
        var cfg = smallCfg();
        var storage = new CompactStorage(tmp.resolve("s"));
        var counter = new FailureCounter(cfg.failureThreshold());
        var ctx = new CompactContext(cfg, storage, counter);

        var offloader = mock(Offloader.class);
        when(offloader.apply(any(), any()))
            .thenThrow(new CompactException("disk full"));

        var summarizer = mock(ConversationSummarizer.class);
        var coordinator = new CompactCoordinator(ctx, mockProvider(), offloader, summarizer);

        var session = mockSession(largeMessages());
        var outcome = coordinator.beforeRequest(session, CompactTrigger.AUTO, null);

        assertInstanceOf(CompactResult.FailedOffload.class, outcome.result());
        var result = (CompactResult.FailedOffload) outcome.result();
        assertTrue(result.reason().contains("disk full"), "Reason should contain exception message");
        assertNull(outcome.newMessages(), "Failed should have null newMessages");
        assertEquals(0, counter.failures(), "Offload failure should NOT increment counter");
        verifyNoInteractions(summarizer);
    }

    // ---- Test 8: resetCounterClearsState ----

    @Test
    void resetCounterClearsState(@TempDir Path tmp) throws Exception {
        var cfg = smallCfg();
        var storage = new CompactStorage(tmp.resolve("s"));
        var counter = new FailureCounter(cfg.failureThreshold());
        var ctx = new CompactContext(cfg, storage, counter);

        var coordinator = new CompactCoordinator(ctx, mockProvider(),
            mock(Offloader.class), mock(ConversationSummarizer.class));

        // Trip the counter
        counter.recordFailure();
        counter.recordFailure();
        counter.recordFailure();
        assertTrue(counter.isTripped());
        assertEquals(3, counter.failures());

        coordinator.resetCounter();

        assertFalse(counter.isTripped(), "Counter should not be tripped after reset");
        assertEquals(0, counter.failures(), "Failures should be 0 after reset");
    }
}
