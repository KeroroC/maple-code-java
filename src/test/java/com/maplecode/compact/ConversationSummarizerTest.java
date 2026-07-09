package com.maplecode.compact;

import com.maplecode.provider.*;
import com.maplecode.provider.ChatMessage.Role;
import com.maplecode.provider.ContentBlock.TextBlock;
import com.maplecode.prompt.SystemBlock;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class ConversationSummarizerTest {

    private static final String VALID_SUMMARY = """
        <scratchpad>
        Internal analysis: user asked to build a calculator.
        Tools called: read_file, write_file.
        No errors.
        </scratchpad>

        ## Intent
        The user wants to build a simple calculator in Java.

        ## Decisions
        Chose to use a single Calculator.java file with static methods.

        ## Open Questions
        None identified.

        ## State
        Calculator.java created at /tmp/Calculator.java with add/subtract methods.

        ## Next Step
        Add multiply and divide methods to complete the calculator.
        """;

    private static final CompactConfig CFG = new CompactConfig(
        200_000, 13_000, 3_000,
        8_000, 30_000,
        100, 5,  // 使用更小的 recencyTokens
        8, 4,
        3);

    private LlmProvider mockProviderReturning(String text) {
        LlmProvider p = mock(LlmProvider.class);
        doAnswer(inv -> {
            Consumer<StreamChunk> sink = inv.getArgument(1);
            sink.accept(new StreamChunk.MessageStart());
            for (int i = 0; i < text.length(); i += 20) {
                sink.accept(new StreamChunk.TextDelta(
                    text.substring(i, Math.min(i + 20, text.length()))));
            }
            sink.accept(new StreamChunk.MessageEnd(StreamChunk.StopReason.END_TURN,
                new TokenUsage(100, 50, 0, 0)));
            return null;
        }).when(p).stream(any(), any());
        return p;
    }

    private List<ChatMessage> buildMessages(int count) {
        List<ChatMessage> msgs = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            if (i % 2 == 0) {
                msgs.add(new ChatMessage(Role.USER,
                    List.of(new TextBlock("User message " + i))));
            } else {
                msgs.add(new ChatMessage(Role.ASSISTANT,
                    List.of(new TextBlock("Assistant response " + i))));
            }
        }
        return msgs;
    }

    @Test
    void successProducesSummaryUserThenTail() {
        LlmProvider provider = mockProviderReturning(VALID_SUMMARY);
        var summarizer = new ConversationSummarizer(provider, "model-main", null);
        // 使用更多的消息，确保 tailStart > 0
        List<ChatMessage> messages = buildMessages(30);

        List<ChatMessage> result = summarizer.apply(messages, CFG);

        // Debug: print result structure
        System.out.println("Result size: " + result.size());
        System.out.println("Original size: " + messages.size());

        // Expect: [summary USER with boundary] + recency tail
        assertTrue(result.size() >= 2, "Expected at least 2 messages");

        // First message: summary as USER (with boundary merged)
        assertEquals(Role.USER, result.get(0).role());
        String summaryText = ((TextBlock) result.get(0).blocks().get(0)).text();
        assertTrue(summaryText.startsWith("[Conversation summary]"),
            "Summary should start with prefix");
        assertTrue(summaryText.contains("## Intent"), "Should contain Intent section");
        assertTrue(summaryText.contains("## Decisions"), "Should contain Decisions section");
        assertTrue(summaryText.contains("## Open Questions"), "Should contain Open Questions section");
        assertTrue(summaryText.contains("## State"), "Should contain State section");
        assertTrue(summaryText.contains("## Next Step"), "Should contain Next Step section");
        assertFalse(summaryText.contains("<scratchpad>"), "Scratchpad should be stripped");
        assertFalse(summaryText.contains("</scratchpad>"), "Scratchpad should be stripped");
        assertTrue(summaryText.contains("[Compact boundary]"),
            "Boundary should be merged into summary");
        assertTrue(summaryText.contains("Do NOT guess code"),
            "Boundary should warn against guessing");
    }

    @Test
    void recencyBelowMinMessagesExtendsTo5() {
        LlmProvider provider = mockProviderReturning(VALID_SUMMARY);
        var summarizer = new ConversationSummarizer(provider, "model-main", null);
        // 11 messages; with recencyMinMessages=5, recency tail should be at least 5
        List<ChatMessage> messages = buildMessages(11);

        List<ChatMessage> result = summarizer.apply(messages, CFG);

        // result = [summary] + tail + [boundary]
        int tailSize = result.size() - 2; // subtract summary and boundary
        assertTrue(tailSize >= 5,
            "Recency tail should be at least 5 messages, got " + tailSize);
    }

    @Test
    void missingSectionThrows() {
        String incompleteSummary = """
            <scratchpad>
            Analysis here.
            </scratchpad>

            ## Intent
            Build a calculator.

            ## Decisions
            Used Java.

            ## Open Questions
            None.

            ## State
            In progress.
            """;
        // Missing "## Next Step"
        LlmProvider provider = mockProviderReturning(incompleteSummary);
        var summarizer = new ConversationSummarizer(provider, "model-main", null);
        List<ChatMessage> messages = buildMessages(10);

        assertThrows(CompactException.class,
            () -> summarizer.apply(messages, CFG),
            "Missing section should throw CompactException");
    }

    @Test
    void refusalThrows() {
        String refusal = "I can't summarize this conversation.";
        LlmProvider provider = mockProviderReturning(refusal);
        var summarizer = new ConversationSummarizer(provider, "model-main", null);
        List<ChatMessage> messages = buildMessages(10);

        assertThrows(CompactException.class,
            () -> summarizer.apply(messages, CFG),
            "Refusal should throw CompactException");
    }

    @Test
    void refusalMarkerInBodyDoesNotThrow() {
        // 正文引用"I can't"不应被误判为拒绝
        String summaryWithQuote = """
            <scratchpad>
            Analysis: user mentioned API issues.
            </scratchpad>

            ## Intent
            The user wants to fix the API integration.

            ## Decisions
            None yet.

            ## Open Questions
            The user said "I can't access that API" — unclear if auth or network issue.

            ## State
            Investigating.

            ## Next Step
            Ask the user for the exact error message.
            """;
        LlmProvider provider = mockProviderReturning(summaryWithQuote);
        var summarizer = new ConversationSummarizer(provider, "model-main", null);
        List<ChatMessage> messages = buildMessages(10);

        assertDoesNotThrow(() -> summarizer.apply(messages, CFG),
            "Refusal marker quoted in body should not be treated as refusal");
    }

    @Test
    void errorChunkThrows() {
        LlmProvider provider = mock(LlmProvider.class);
        doAnswer(inv -> {
            Consumer<StreamChunk> sink = inv.getArgument(1);
            sink.accept(new StreamChunk.MessageStart());
            sink.accept(new StreamChunk.Error("rate_limit", "Rate limit exceeded"));
            return null;
        }).when(provider).stream(any(), any());

        var summarizer = new ConversationSummarizer(provider, "model-main", null);
        List<ChatMessage> messages = buildMessages(10);

        assertThrows(CompactException.class,
            () -> summarizer.apply(messages, CFG),
            "Error chunk should throw CompactException");
    }

    @Test
    void summarizerModelUsedWhenConfigured() {
        LlmProvider provider = mockProviderReturning(VALID_SUMMARY);
        String summarizerModel = "claude-sonnet-4-20250514";
        var summarizer = new ConversationSummarizer(provider, "model-main", summarizerModel);
        List<ChatMessage> messages = buildMessages(10);

        summarizer.apply(messages, CFG);

        // Verify the provider.stream was called with summarizerModel
        var captor = ArgumentCaptor.forClass(ChatRequest.class);
        verify(provider).stream(captor.capture(), any());
        assertEquals(summarizerModel, captor.getValue().model(),
            "Should use summarizerModel when configured");
    }

    @Test
    void mainModelUsedWhenSummarizerModelNull() {
        LlmProvider provider = mockProviderReturning(VALID_SUMMARY);
        var summarizer = new ConversationSummarizer(provider, "model-main", null);
        List<ChatMessage> messages = buildMessages(10);

        summarizer.apply(messages, CFG);

        var captor = ArgumentCaptor.forClass(ChatRequest.class);
        verify(provider).stream(captor.capture(), any());
        assertEquals("model-main", captor.getValue().model(),
            "Should fallback to mainModel when summarizerModel is null");
    }

    @Test
    void noConsecutiveUserMessages() {
        // 构造典型场景：尾部末条是 USER（tool_result）
        LlmProvider provider = mockProviderReturning(VALID_SUMMARY);
        var summarizer = new ConversationSummarizer(provider, "model-main", null);

        List<ChatMessage> messages = new ArrayList<>();
        // 前面的消息（会被压缩）- 需要足够多的消息确保 tailStart > 0
        for (int i = 0; i < 15; i++) {
            if (i % 2 == 0) {
                messages.add(new ChatMessage(Role.USER, List.of(new TextBlock("User message " + i))));
            } else {
                messages.add(new ChatMessage(Role.ASSISTANT, List.of(new TextBlock("Assistant response " + i))));
            }
        }
        // 尾部消息（会保留）- 末条是 USER
        messages.add(new ChatMessage(Role.ASSISTANT, List.of(new TextBlock("Tool call"))));
        messages.add(new ChatMessage(Role.USER, List.of(new TextBlock("Tool result"))));  // 尾部末条是 USER

        List<ChatMessage> result = summarizer.apply(messages, CFG);

        // Debug: print result structure
        System.out.println("Result size: " + result.size());
        for (int i = 0; i < result.size(); i++) {
            String text = ((TextBlock) result.get(i).blocks().get(0)).text();
            System.out.println("  [" + i + "] " + result.get(i).role() + ": " +
                text.substring(0, Math.min(50, text.length())));
        }

        // 验证没有连续 USER 消息
        for (int i = 1; i < result.size(); i++) {
            Role prev = result.get(i - 1).role();
            Role curr = result.get(i).role();
            assertFalse(prev == Role.USER && curr == Role.USER,
                "Found consecutive USER messages at index " + (i - 1) + " and " + i);
        }
    }

    @Test
    void noConsecutiveUserMessagesWhenTailStartsWithUser() {
        // 构造场景：尾部首条是 USER
        LlmProvider provider = mockProviderReturning(VALID_SUMMARY);
        var summarizer = new ConversationSummarizer(provider, "model-main", null);

        List<ChatMessage> messages = new ArrayList<>();
        // 前面的消息（会被压缩）
        messages.add(new ChatMessage(Role.ASSISTANT, List.of(new TextBlock("Assistant message 0"))));
        messages.add(new ChatMessage(Role.USER, List.of(new TextBlock("User message 1"))));
        messages.add(new ChatMessage(Role.ASSISTANT, List.of(new TextBlock("Assistant message 2"))));
        // 尾部消息（会保留）- 首条是 USER
        messages.add(new ChatMessage(Role.USER, List.of(new TextBlock("User message 3"))));  // 尾部首条是 USER
        messages.add(new ChatMessage(Role.ASSISTANT, List.of(new TextBlock("Assistant response 4"))));

        List<ChatMessage> result = summarizer.apply(messages, CFG);

        // 验证没有连续 USER 消息
        for (int i = 1; i < result.size(); i++) {
            Role prev = result.get(i - 1).role();
            Role curr = result.get(i).role();
            assertFalse(prev == Role.USER && curr == Role.USER,
                "Found consecutive USER messages at index " + (i - 1) + " and " + i);
        }
    }
}
