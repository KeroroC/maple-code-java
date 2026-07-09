package com.maplecode.command;

import com.maplecode.compact.CompactCoordinator;
import com.maplecode.session.archive.SessionArchive;

public class NewCommand implements Command {
    private final SessionArchive archive;
    private final CompactCoordinator coord;

    public NewCommand(SessionArchive archive, CompactCoordinator coord) {
        this.archive = archive;
        this.coord = coord;
    }

    @Override public String name() { return "new"; }
    @Override public String description() { return "归档当前会话并清空"; }
    @Override public String usage() { return "/new"; }
    @Override public CommandType type() { return CommandType.LOCAL; }
    @Override public boolean hidden() { return false; }

    @Override
    public void execute(String args, CommandContext ctx) {
        if (archive != null) {
            archive.save(ctx.getSession());
        }
        ctx.getSession().clear();
        if (coord != null) {
            coord.recordUsage(null);
        }
        ctx.updateStatusBar();
    }
}
