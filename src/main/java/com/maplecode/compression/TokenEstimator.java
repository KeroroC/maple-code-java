package com.maplecode.compression;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.maplecode.provider.ChatMessage;
import com.maplecode.provider.ContentBlock;
import com.maplecode.provider.TokenUsage;

import java.util.List;
import java.util.Map;

public final class TokenEstimator {

    private static final ObjectMapper JSON = new ObjectMapper();

    public int estimate(List<ChatMessage> messages, TokenUsage anchor) {
        int anchorTokens = 0;
        if (anchor != null) {
            anchorTokens = anchor.inputTokens()
                + anchor.cacheCreationTokens()
                + anchor.cacheReadTokens();
        }
        long chars = 0;
        for (var msg : messages) {
            for (var block : msg.blocks()) {
                chars += blockChars(block);
            }
        }
        return anchorTokens + (int) (chars / 4);
    }

    private long blockChars(ContentBlock block) {
        if (block instanceof ContentBlock.TextBlock tb) {
            return tb.text().length();
        }
        if (block instanceof ContentBlock.ToolUseBlock tu) {
            String json;
            try {
                json = JSON.writeValueAsString(Map.of(
                    "id", tu.id(),
                    "name", tu.name(),
                    "input", tu.input()));
            } catch (JsonProcessingException e) {
                json = tu.id() + tu.name() + tu.input().toString();
            }
            return json.length();
        }
        if (block instanceof ContentBlock.ToolResultBlock tr) {
            return tr.content().length();
        }
        return 0;
    }
}
