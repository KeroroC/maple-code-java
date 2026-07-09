package com.maplecode.command;

import com.maplecode.tool.ToolRegistry;

public class ToolsCommand implements Command {
    private final ToolRegistry registry;

    public ToolsCommand(ToolRegistry registry) {
        this.registry = registry;
    }

    @Override public String name() { return "tools"; }
    @Override public String description() { return "列出所有可用工具"; }
    @Override public String usage() { return "/tools"; }
    @Override public CommandType type() { return CommandType.LOCAL; }
    @Override public boolean hidden() { return false; }

    @Override
    public void execute(String args, CommandContext ctx) {
        registry.all().forEach(tool ->
            ctx.sendMessage("- " + tool.name() + ": " + tool.description()));
    }
}
