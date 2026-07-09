package com.maplecode.command;

import com.maplecode.compact.CompactCoordinator;

public class ClearCommand implements Command {
    private final CompactCoordinator coord;

    public ClearCommand(CompactCoordinator coord) {
        this.coord = coord;
    }

    @Override public String name() { return "clear"; }
    @Override public String description() { return "清空会话历史"; }
    @Override public String usage() { return "/clear"; }
    @Override public CommandType type() { return CommandType.LOCAL; }
    @Override public boolean hidden() { return false; }

    @Override
    public void execute(String args, CommandContext ctx) {
        ctx.getSession().clear();
        if (coord != null) {
            coord.recordUsage(null);
        }
        ctx.updateStatusBar();
    }
}
