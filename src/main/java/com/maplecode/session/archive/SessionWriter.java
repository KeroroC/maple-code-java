package com.maplecode.session.archive;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.maplecode.provider.ChatMessage;
import com.maplecode.provider.ContentBlock;
import com.maplecode.session.ChatSession;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;

import com.maplecode.util.IoUtil;

final class SessionWriter {

    private static final ObjectMapper JSON = new ObjectMapper();

    int write(ChatSession session, Path target) {
        try {
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < session.size(); i++) {
                ChatMessage msg = session.get(i);
                ObjectNode node = JSON.createObjectNode();
                node.put("role", msg.role().name().toLowerCase());
                node.set("content", serializeBlocks(msg.blocks()));
                sb.append(JSON.writeValueAsString(node)).append('\n');
            }
            IoUtil.atomicWrite(target, sb.toString());
            return session.size();
        } catch (IOException e) {
            throw new SessionArchiveException("write failed: " + target, e);
        }
    }

    private ArrayNode serializeBlocks(List<ContentBlock> blocks) {
        ArrayNode arr = JSON.createArrayNode();
        for (ContentBlock block : blocks) {
            arr.add(serializeBlock(block));
        }
        return arr;
    }

    private ObjectNode serializeBlock(ContentBlock block) {
        ObjectNode node = JSON.createObjectNode();
        switch (block) {
            case ContentBlock.TextBlock t -> {
                node.put("type", "text");
                node.put("text", t.text());
            }
            case ContentBlock.ToolUseBlock t -> {
                node.put("type", "tool_use");
                node.put("id", t.id());
                node.put("name", t.name());
                node.set("input", t.input());
            }
            case ContentBlock.ToolResultBlock t -> {
                node.put("type", "tool_result");
                node.put("toolUseId", t.toolUseId());
                node.put("content", t.content());
                node.put("isError", t.isError());
            }
        }
        return node;
    }
}
