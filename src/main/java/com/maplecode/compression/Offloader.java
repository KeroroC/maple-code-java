package com.maplecode.compression;

import com.maplecode.provider.ChatMessage;
import com.maplecode.provider.ChatMessage.Role;
import com.maplecode.provider.ContentBlock;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

public final class Offloader {

    private final CompressionStorage storage;
    private final TokenEstimator estimator = new TokenEstimator();

    public Offloader(CompressionStorage storage) {
        this.storage = storage;
    }

    public List<ChatMessage> apply(List<ChatMessage> messages, CompressionConfig config) {
        List<ChatMessage> result = new ArrayList<>(messages.size());
        for (var msg : messages) {
            if (msg.role() != Role.USER) {
                result.add(msg);
                continue;
            }
            var blocks = msg.blocks();
            var offloadSet = new java.util.HashSet<Integer>();
            var toolResultIndices = new ArrayList<Integer>();

            // First pass: identify tool results that exceed single threshold
            for (int i = 0; i < blocks.size(); i++) {
                if (blocks.get(i) instanceof ContentBlock.ToolResultBlock tr) {
                    int tokens = estimator.estimate(List.of(
                        new ChatMessage(Role.USER, List.of(tr))), null);
                    toolResultIndices.add(i);
                    if (tokens > config.singleToolResultOffloadTokens()) {
                        offloadSet.add(i);
                    }
                }
            }

            // If no tool results, continue
            if (toolResultIndices.isEmpty()) {
                result.add(msg);
                continue;
            }

            // Check aggregate threshold for remaining tool results (not already marked)
            int sumTokens = 0;
            for (int i : toolResultIndices) {
                if (!offloadSet.contains(i)) {
                    sumTokens += estimator.estimate(List.of(
                        new ChatMessage(Role.USER, List.of(blocks.get(i)))), null);
                }
            }

            // If aggregate exceeds threshold, sort and offload until under threshold
            if (sumTokens > config.messageToolResultAggregateTokens()) {
                var remaining = new ArrayList<>(toolResultIndices.stream()
                    .filter(i -> !offloadSet.contains(i))
                    .toList());
                remaining.sort(Comparator.comparingInt((Integer i) ->
                    estimator.estimate(List.of(
                        new ChatMessage(Role.USER, List.of(blocks.get(i)))), null)
                ).reversed());

                int currentSum = sumTokens;
                for (int idx : remaining) {
                    if (currentSum <= config.messageToolResultAggregateTokens()) break;
                    int t = estimator.estimate(List.of(
                        new ChatMessage(Role.USER, List.of(blocks.get(idx)))), null);
                    offloadSet.add(idx);
                    currentSum -= t;
                }
            }

            // If nothing to offload, return original message
            if (offloadSet.isEmpty()) {
                result.add(msg);
                continue;
            }

            // Build new blocks
            var newBlocks = new ArrayList<ContentBlock>(blocks.size());
            for (int i = 0; i < blocks.size(); i++) {
                var b = blocks.get(i);
                if (offloadSet.contains(i) && b instanceof ContentBlock.ToolResultBlock tr) {
                    var saved = storage.write(tr.content());
                    String preview = storage.buildPreview(saved, tr.content(),
                        config.previewHeadLines(), config.previewTailLines());
                    newBlocks.add(new ContentBlock.ToolResultBlock(tr.toolUseId(), preview, tr.isError()));
                } else {
                    newBlocks.add(b);
                }
            }
            result.add(new ChatMessage(msg.role(), newBlocks));
        }
        return result;
    }
}
