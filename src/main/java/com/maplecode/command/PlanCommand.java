package com.maplecode.command;

import com.maplecode.agent.PlanMode;

public class PlanCommand implements Command {
    @Override public String name() { return "plan"; }
    @Override public String description() { return "进入计划模式并发送查询"; }
    @Override public String usage() { return "/plan <query>"; }
    @Override public CommandType type() { return CommandType.PROMPT; }
    @Override public boolean hidden() { return false; }

    @Override
    public void execute(String args, CommandContext ctx) {
        if (args.isEmpty()) {
            ctx.sendError("用法: /plan <query>");
            return;
        }
        ctx.setPlanMode(PlanMode.PLAN);
        ctx.sendToAgent(args);
    }
}
