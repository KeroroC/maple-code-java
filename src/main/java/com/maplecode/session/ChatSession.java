package com.maplecode.session;

import com.maplecode.provider.ChatMessage;
import com.maplecode.provider.ChatMessage.Role;
import com.maplecode.provider.ChatRequest;
import com.maplecode.provider.ThinkingConfig;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public final class ChatSession {

    private final List<ChatMessage> messages = new ArrayList<>();

    public void appendUser(String text) {
        messages.add(new ChatMessage(Role.USER, text));
    }

    public void appendAssistant(String text) {
        messages.add(new ChatMessage(Role.ASSISTANT, text));
    }

    public void clear() {
        messages.clear();
    }

    public ChatRequest toRequest(String model, String systemPrompt, ThinkingConfig thinking) {
        return new ChatRequest(model, systemPrompt,
            Collections.unmodifiableList(new ArrayList<>(messages)), thinking);
    }
}