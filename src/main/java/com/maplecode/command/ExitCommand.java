package com.maplecode.command;

public class ExitCommand implements Command {
    @Override public String name() { return "exit"; }
    @Override public String description() { return "退出程序"; }
    @Override public String usage() { return "/exit"; }
    @Override public CommandType type() { return CommandType.LOCAL; }
    @Override public boolean hidden() { return false; }

    @Override
    public void execute(String args, CommandContext ctx) {
        throw new ExitReplException();
    }
}
