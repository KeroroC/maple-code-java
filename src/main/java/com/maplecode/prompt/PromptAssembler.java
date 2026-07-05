package com.maplecode.prompt;

import com.maplecode.provider.ChatMessage;
import com.maplecode.provider.ChatMessage.Role;
import com.maplecode.provider.ChatRequest;
import com.maplecode.provider.ContentBlock;

import java.util.ArrayList;
import java.util.List;

public final class PromptAssembler {

    public List<SystemBlock> assemble(List<PromptSection> sections, SectionContext ctx) {
        List<SystemBlock> blocks = new ArrayList<>();
        int lastCacheableIdx = -1;
        for (PromptSection s : sections) {
            if (!s.enabled(ctx)) continue;
            String text = s.render(ctx);
            if (text == null || text.isBlank()) continue;
            blocks.add(new SystemBlock(text, false, s.kind()));
            if (s.cacheable()) lastCacheableIdx = blocks.size() - 1;
        }
        if (lastCacheableIdx >= 0) {
            SystemBlock tail = blocks.get(lastCacheableIdx);
            blocks.set(lastCacheableIdx,
                new SystemBlock(tail.content(), true, tail.kind()));
        }
        return blocks;
    }

    public ChatRequest attachReminder(ChatRequest req, String reminderBody) {
        if (reminderBody == null || reminderBody.isBlank()) return req;
        String wrapped = ReminderMessage.wrap(reminderBody);
        List<ChatMessage> newMsgs = new ArrayList<>(req.messages());
        newMsgs.add(new ChatMessage(Role.USER,
            List.of(new ContentBlock.TextBlock(wrapped))));
        return new ChatRequest(req.model(), req.systemBlocks(), newMsgs,
            req.thinking(), req.tools());
    }
}
