package com.maplecode.session.archive;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.maplecode.provider.ChatMessage;
import com.maplecode.provider.ContentBlock;

import java.io.BufferedReader;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

final class SessionReader {

    private static final ObjectMapper JSON = new ObjectMapper();

    List<ChatMessage> read(Path file) {
        List<ChatMessage> messages = new ArrayList<>();
        try (BufferedReader r = Files.newBufferedReader(file)) {
            String line;
            int lineNum = 0;
            while ((line = r.readLine()) != null) {
                lineNum++;
                if (line.isBlank()) continue;
                try {
                    JsonNode root = JSON.readTree(line);
                    messages.add(parseMessage(root));
                } catch (Exception e) {
                    System.err.println("[session] WARN: skipped malformed line " + lineNum);
                }
            }
        } catch (IOException e) {
            throw new SessionArchiveException("read failed: " + file, e);
        }
        truncateOrphanToolUse(messages);
        return messages;
    }

    private ChatMessage parseMessage(JsonNode root) {
        String roleStr = root.get("role").asText();
        ChatMessage.Role role = ChatMessage.Role.valueOf(roleStr.toUpperCase());
        List<ContentBlock> blocks = new ArrayList<>();
        JsonNode content = root.get("content");
        if (content != null && content.isArray()) {
            for (JsonNode item : content) {
                blocks.add(parseBlock(item));
            }
        }
        return new ChatMessage(role, List.copyOf(blocks));
    }

    private ContentBlock parseBlock(JsonNode node) {
        String type = node.get("type").asText();
        return switch (type) {
            case "text" -> new ContentBlock.TextBlock(node.get("text").asText());
            case "tool_use" -> new ContentBlock.ToolUseBlock(
                node.get("id").asText(),
                node.get("name").asText(),
                node.get("input"));
            case "tool_result" -> new ContentBlock.ToolResultBlock(
                node.get("toolUseId").asText(),
                node.get("content").asText(),
                node.has("isError") && node.get("isError").asBoolean());
            default -> throw new SessionArchiveException("unknown block type: " + type);
        };
    }

    private void truncateOrphanToolUse(List<ChatMessage> messages) {
        Set<String> toolUseIds = new HashSet<>();
        Set<String> toolResultIds = new HashSet<>();
        for (ChatMessage msg : messages) {
            for (ContentBlock block : msg.blocks()) {
                switch (block) {
                    case ContentBlock.ToolUseBlock t -> toolUseIds.add(t.id());
                    case ContentBlock.ToolResultBlock t -> toolResultIds.add(t.toolUseId());
                    default -> {}
                }
            }
        }
        // 正向孤儿：tool_use 没有对应 tool_result
        Set<String> orphanToolUseIds = new HashSet<>(toolUseIds);
        orphanToolUseIds.removeAll(toolResultIds);
        // 反向孤儿：tool_result 没有对应 tool_use
        Set<String> orphanToolResultIds = new HashSet<>(toolResultIds);
        orphanToolResultIds.removeAll(toolUseIds);

        if (orphanToolUseIds.isEmpty() && orphanToolResultIds.isEmpty()) return;

        for (int i = 0; i < messages.size(); i++) {
            ChatMessage msg = messages.get(i);
            List<ContentBlock> filtered = new ArrayList<>();
            boolean changed = false;
            for (ContentBlock block : msg.blocks()) {
                if (block instanceof ContentBlock.ToolUseBlock t && orphanToolUseIds.contains(t.id())) {
                    changed = true;
                    System.err.println("[session] WARN: truncated orphan tool_use " + t.id());
                } else if (block instanceof ContentBlock.ToolResultBlock t && orphanToolResultIds.contains(t.toolUseId())) {
                    changed = true;
                    System.err.println("[session] WARN: truncated orphan tool_result " + t.toolUseId());
                } else {
                    filtered.add(block);
                }
            }
            if (changed) {
                messages.set(i, new ChatMessage(msg.role(), List.copyOf(filtered)));
            }
        }
    }
}
