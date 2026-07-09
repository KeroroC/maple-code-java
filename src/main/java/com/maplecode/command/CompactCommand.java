package com.maplecode.command;

import com.maplecode.compact.CompactCoordinator;
import com.maplecode.compact.CompactResult;
import com.maplecode.compact.CompactTrigger;

public class CompactCommand implements Command {
    private final CompactCoordinator coord;

    public CompactCommand(CompactCoordinator coord) {
        this.coord = coord;
    }

    @Override public String name() { return "compact"; }
    @Override public String description() { return "手动触发上下文压缩"; }
    @Override public String usage() { return "/compact"; }
    @Override public CommandType type() { return CommandType.UI_STATE; }
    @Override public boolean hidden() { return false; }

    @Override
    public void execute(String args, CommandContext ctx) {
        var outcome = coord.beforeRequest(ctx.getSession(), CompactTrigger.MANUAL, coord.lastSeenUsage());
        if (outcome.result() instanceof CompactResult.ChangedOffloadOnly
            || outcome.result() instanceof CompactResult.ChangedFull) {
            ctx.getSession().replaceAll(outcome.newMessages());
        }
        ctx.sendMessage(outcome.result().toString());
    }
}
