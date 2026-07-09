package com.maplecode.command;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

public class HelpCommand implements Command {
    private final CommandRegistry registry;

    public HelpCommand(CommandRegistry registry) {
        this.registry = registry;
    }

    @Override public String name() { return "help"; }
    @Override public String description() { return "显示帮助信息"; }
    @Override public String usage() { return "/help [command]"; }
    @Override public CommandType type() { return CommandType.LOCAL; }
    @Override public boolean hidden() { return false; }
    @Override public List<String> aliases() { return List.of("h", "?"); }

    @Override
    public void execute(String args, CommandContext ctx) {
        if (!args.isEmpty()) {
            registry.lookup(args).ifPresentOrElse(
                cmd -> ctx.sendMessage(cmd.usage() + " — " + cmd.description()),
                () -> ctx.sendError("未知命令: " + args));
            return;
        }

        List<Command> visible = registry.visible();
        Map<CommandType, List<Command>> grouped = visible.stream()
            .collect(Collectors.groupingBy(Command::type));

        for (CommandType type : CommandType.values()) {
            List<Command> group = grouped.get(type);
            if (group == null || group.isEmpty()) continue;

            String header = switch (type) {
                case LOCAL -> "── 本地命令 ──";
                case UI_STATE -> "── 状态命令 ──";
                case PROMPT -> "── AI 命令 ──";
            };
            ctx.sendMessage(header);
            for (Command cmd : group) {
                ctx.sendMessage("  " + cmd.usage() + " — " + cmd.description());
            }
        }
    }
}
