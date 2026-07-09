package com.maplecode.command;

import com.maplecode.memory.MemoryManager;

public class MemoryCommand implements Command {
    private final MemoryManager manager;

    public MemoryCommand(MemoryManager manager) {
        this.manager = manager;
    }

    @Override public String name() { return "memory"; }
    @Override public String description() { return "记忆管理"; }
    @Override public String usage() { return "/memory <list|clear|extract>"; }
    @Override public CommandType type() { return CommandType.LOCAL; }
    @Override public boolean hidden() { return false; }

    @Override
    public void execute(String args, CommandContext ctx) {
        if (manager == null) {
            ctx.sendError("记忆系统未启用。");
            return;
        }
        switch (args) {
            case "list" -> ctx.sendMessage(manager.listMemories());
            case "clear" -> manager.clearAll();
            case "extract" -> manager.extractSync(ctx.getSession().recentMessages(20));
            default -> ctx.sendError("用法: /memory <list|clear|extract>");
        }
    }
}
