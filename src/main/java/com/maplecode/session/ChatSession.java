package com.maplecode.session;

import com.maplecode.provider.ChatMessage;
import com.maplecode.provider.ChatMessage.Role;
import com.maplecode.provider.ChatRequest;
import com.maplecode.provider.ContentBlock;
import com.maplecode.provider.ThinkingConfig;
import com.maplecode.tool.Tool;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public final class ChatSession {

    private final List<ChatMessage> messages = new ArrayList<>();

    /** 便利方法：用户输入纯文本。 */
    public void appendUserText(String text) {
        appendUser(List.of(new ContentBlock.TextBlock(text)));
    }

    /**
     * 添加 user 消息。会复制 blocks 以防外部修改影响 session 内部状态。
     */
    public void appendUser(List<ContentBlock> blocks) {
        messages.add(new ChatMessage(Role.USER, List.copyOf(blocks)));
    }

    /**
     * 添加 assistant 消息。会复制 blocks 以防外部修改影响 session 内部状态。
     */
    public void appendAssistant(List<ContentBlock> blocks) {
        messages.add(new ChatMessage(Role.ASSISTANT, List.copyOf(blocks)));
    }

    public int size() {
        return messages.size();
    }

    public ChatMessage get(int i) {
        return messages.get(i);
    }

    public void clear() {
        messages.clear();
    }

    public ChatRequest toRequest(String model, String systemPrompt, ThinkingConfig thinking) {
        return new ChatRequest(model, systemPrompt,
            Collections.unmodifiableList(new ArrayList<>(messages)), thinking, null);
    }

    public ChatRequest toRequest(String model, String systemPrompt, ThinkingConfig thinking, List<Tool> tools) {
        return new ChatRequest(model, systemPrompt,
            Collections.unmodifiableList(new ArrayList<>(messages)), thinking, tools);
    }
}