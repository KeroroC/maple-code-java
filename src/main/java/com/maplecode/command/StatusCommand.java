package com.maplecode.command;

import com.maplecode.provider.TokenUsage;

public class StatusCommand implements Command {
    @Override public String name() { return "status"; }
    @Override public String description() { return "显示当前状态"; }
    @Override public String usage() { return "/status"; }
    @Override public CommandType type() { return CommandType.LOCAL; }
    @Override public boolean hidden() { return false; }

    @Override
    public void execute(String args, CommandContext ctx) {
        String model = ctx.getAgentConfig().model();
        TokenUsage usage = ctx.getTokenUsage();
        String tokens = (usage != null)
            ? usage.inputTokens() + " / " + usage.outputTokens()
            : "N/A";
        String mode = ctx.getPermissionMode().name().toLowerCase();
        String plan = ctx.getPlanMode().name().toLowerCase();
        String cwd = System.getProperty("user.dir");

        String status = String.format(
            "Model:    %s\nTokens:   %s\nMode:     %s\nPlan:     %s\nCwd:      %s",
            model, tokens, mode, plan, cwd);
        ctx.sendMessage(status);
    }
}
