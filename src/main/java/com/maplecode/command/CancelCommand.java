package com.maplecode.command;

import com.maplecode.agent.PlanMode;

public class CancelCommand implements Command {
    @Override public String name() { return "cancel"; }
    @Override public String description() { return "中断当前执行"; }
    @Override public String usage() { return "/cancel"; }
    @Override public CommandType type() { return CommandType.LOCAL; }
    @Override public boolean hidden() { return false; }

    @Override
    public void execute(String args, CommandContext ctx) {
        ctx.cancelCurrentAgentRun();
        ctx.setPlanMode(PlanMode.NORMAL);
        ctx.updateStatusBar();
    }
}
