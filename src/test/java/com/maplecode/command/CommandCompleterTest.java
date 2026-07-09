package com.maplecode.command;

import org.junit.jupiter.api.Test;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class CommandCompleterTest {

    @Test
    void completableNames_sortedIncludesAliases() {
        CommandRegistry reg = new CommandRegistry();
        reg.register(new ExitCommand());
        reg.register(new HelpCommand(reg));

        List<String> names = reg.completableNames();
        assertTrue(names.contains("help"));
        assertTrue(names.contains("h"));
        assertTrue(names.contains("?"));
        assertTrue(names.contains("exit"));
        // Verify sorted
        for (int i = 1; i < names.size(); i++) {
            assertTrue(names.get(i - 1).compareTo(names.get(i)) <= 0,
                "Names should be sorted: " + names);
        }
    }

    @Test
    void completableNames_excludesHidden() {
        CommandRegistry reg = new CommandRegistry();
        Command hidden = new Command() {
            @Override public String name() { return "internal"; }
            @Override public String description() { return ""; }
            @Override public String usage() { return ""; }
            @Override public CommandType type() { return CommandType.LOCAL; }
            @Override public boolean hidden() { return true; }
            @Override public void execute(String args, CommandContext ctx) {}
        };
        reg.register(hidden);
        reg.register(new ExitCommand());

        List<String> names = reg.completableNames();
        assertFalse(names.contains("internal"));
        assertTrue(names.contains("exit"));
    }
}
