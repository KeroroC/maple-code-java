package com.maplecode.compact;

import com.maplecode.provider.ChatMessage;
import com.maplecode.provider.ChatMessage.Role;
import com.maplecode.provider.ContentBlock;
import com.maplecode.provider.TokenUsage;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class TokenEstimatorTest {

    private final TokenEstimator est = new TokenEstimator();

    @Test
    void emptyListIsZero() {
        assertEquals(0, est.estimate(List.of(), null));
    }

    @Test
    void singleTextBlockCharsDividedBy4() {
        var msg = new ChatMessage(Role.USER,
            List.of(new ContentBlock.TextBlock("x".repeat(1000))));
        assertEquals(250, est.estimate(List.of(msg), null));
    }

    @Test
    void anchorPlusDeltaIsSummed() {
        var msg = new ChatMessage(Role.USER,
            List.of(new ContentBlock.TextBlock("x".repeat(800))));
        var anchor = new TokenUsage(1000, 200, 0, 0);
        // max(1000 anchor, 800/4=200 chars) = 1000
        assertEquals(1000, est.estimate(List.of(msg), anchor));
    }

    @Test
    void anchorWithCacheCounted() {
        var msg = new ChatMessage(Role.USER,
            List.of(new ContentBlock.TextBlock("x".repeat(400))));
        var anchor = new TokenUsage(500, 100, 200, 300);
        // max(500+200+300=1000 anchor, 400/4=100 chars) = 1000
        assertEquals(1000, est.estimate(List.of(msg), anchor));
    }

    @Test
    void toolResultBlockCountedByContentLength() {
        var msg = new ChatMessage(Role.USER,
            List.of(new ContentBlock.ToolResultBlock("tu-1", "y".repeat(100_000), false)));
        // 100_000 / 4 = 25_000
        assertEquals(25_000, est.estimate(List.of(msg), null));
    }

    @Test
    void toolUseBlockCountedByJsonSerialization() {
        var msg = new ChatMessage(Role.ASSISTANT,
            List.of(new ContentBlock.ToolUseBlock("tu-1", "read_file",
                new com.fasterxml.jackson.databind.ObjectMapper().createObjectNode().put("path", "/a/b"))));
        int t = est.estimate(List.of(msg), null);
        assertTrue(t > 0 && t < 200, "tool_use block 估 token 应在 (0, 200)，实际 " + t);
    }

    @Test
    void multipleBlocksSummed() {
        var msg1 = new ChatMessage(Role.USER,
            List.of(new ContentBlock.TextBlock("x".repeat(400))));
        var msg2 = new ChatMessage(Role.ASSISTANT,
            List.of(new ContentBlock.TextBlock("y".repeat(800))));
        // 1200 / 4 = 300
        assertEquals(300, est.estimate(List.of(msg1, msg2), null));
    }
}
