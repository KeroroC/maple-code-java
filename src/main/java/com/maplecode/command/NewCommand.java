package com.maplecode.command;

import com.maplecode.compact.CompactCoordinator;
import com.maplecode.session.archive.SessionArchive;
import com.maplecode.skill.SkillRegistry;

public class NewCommand implements Command {
    private final SessionArchive archive;
    private final CompactCoordinator coord;
    private final SkillRegistry skillRegistry;

    public NewCommand(SessionArchive archive, CompactCoordinator coord, SkillRegistry skillRegistry) {
        this.archive = archive;
        this.coord = coord;
        this.skillRegistry = skillRegistry;
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
        if (skillRegistry != null) {
            skillRegistry.deactivateAll();
        }
        ctx.updateStatusBar();
    }
}
