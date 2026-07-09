package com.maplecode.command;

import com.maplecode.agent.PlanMode;
import com.maplecode.provider.ChatMessage;
import com.maplecode.provider.ContentBlock;
import com.maplecode.session.ChatSession;

public class DoCommand implements Command {
    @Override public String name() { return "do"; }
    @Override public String description() { return "执行上一条计划"; }
    @Override public String usage() { return "/do"; }
    @Override public CommandType type() { return CommandType.PROMPT; }
    @Override public boolean hidden() { return false; }

    @Override
    public void execute(String args, CommandContext ctx) {
        if (ctx.getPlanMode() != PlanMode.PLAN) {
            ctx.sendError("当前不在计划模式。先用 /plan 进入计划模式。");
            return;
        }

        String planText = lastAssistantText(ctx.getSession());
        if (planText == null || planText.isBlank()) {
            ctx.sendError("没有找到计划内容。");
            return;
        }

        ctx.getSession().clear();
        ctx.setPlanMode(PlanMode.NORMAL);
        ctx.sendToAgent(planText);
    }

    private String lastAssistantText(ChatSession session) {
        for (int i = session.size() - 1; i >= 0; i--) {
            ChatMessage msg = session.get(i);
            if (msg.role() == ChatMessage.Role.ASSISTANT) {
                for (ContentBlock block : msg.blocks()) {
                    if (block instanceof ContentBlock.TextBlock tb) {
                        return tb.text();
                    }
                }
            }
        }
        return null;
    }
}
