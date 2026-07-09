package com.maplecode.command;

import org.junit.jupiter.api.Test;
import java.util.List;
import java.util.Optional;
import static org.junit.jupiter.api.Assertions.*;

class CommandRegistryTest {

    private Command stubCommand(String name, String... aliases) {
        return new Command() {
            @Override public String name() { return name; }
            @Override public String description() { return "desc"; }
            @Override public String usage() { return "/" + name; }
            @Override public CommandType type() { return CommandType.LOCAL; }
            @Override public boolean hidden() { return false; }
            @Override public List<String> aliases() { return List.of(aliases); }
            @Override public void execute(String args, CommandContext ctx) {}
        };
    }

    private Command hiddenCommand(String name) {
        return new Command() {
            @Override public String name() { return name; }
            @Override public String description() { return "hidden"; }
            @Override public String usage() { return "/" + name; }
            @Override public CommandType type() { return CommandType.LOCAL; }
            @Override public boolean hidden() { return true; }
            @Override public void execute(String args, CommandContext ctx) {}
        };
    }

    @Test
    void registerAndLookup_byName() {
        CommandRegistry reg = new CommandRegistry();
        Command help = stubCommand("help", "h", "?");
        reg.register(help);

        Optional<Command> found = reg.lookup("help");
        assertTrue(found.isPresent());
        assertSame(help, found.get());
    }

    @Test
    void registerAndLookup_byAlias() {
        CommandRegistry reg = new CommandRegistry();
        Command help = stubCommand("help", "h", "?");
        reg.register(help);

        assertEquals(help, reg.lookup("h").get());
        assertEquals(help, reg.lookup("?").get());
    }

    @Test
    void lookup_caseInsensitive() {
        CommandRegistry reg = new CommandRegistry();
        reg.register(stubCommand("help"));

        assertEquals("help", reg.lookup("HELP").get().name());
    }

    @Test
    void lookup_notFound_returnsEmpty() {
        CommandRegistry reg = new CommandRegistry();
        assertTrue(reg.lookup("nonexistent").isEmpty());
    }

    @Test
    void register_duplicateName_throws() {
        CommandRegistry reg = new CommandRegistry();
        reg.register(stubCommand("help"));

        assertThrows(IllegalArgumentException.class, () ->
            reg.register(stubCommand("help")));
    }

    @Test
    void register_aliasConflictsWithName_throws() {
        CommandRegistry reg = new CommandRegistry();
        reg.register(stubCommand("help", "h"));

        assertThrows(IllegalArgumentException.class, () ->
            reg.register(stubCommand("h")));
    }

    @Test
    void register_aliasConflictsWithAlias_throws() {
        CommandRegistry reg = new CommandRegistry();
        reg.register(stubCommand("help", "h"));

        assertThrows(IllegalArgumentException.class, () ->
            reg.register(stubCommand("clear", "h")));
    }

    @Test
    void register_aliasEqualsOwnName_throws() {
        CommandRegistry reg = new CommandRegistry();

        assertThrows(IllegalArgumentException.class, () ->
            reg.register(stubCommand("help", "help")));
    }

    @Test
    void visible_excludesHidden() {
        CommandRegistry reg = new CommandRegistry();
        reg.register(stubCommand("help"));
        reg.register(hiddenCommand("internal"));

        List<Command> visible = reg.visible();
        assertEquals(1, visible.size());
        assertEquals("help", visible.get(0).name());
    }

    @Test
    void visible_sortedByName() {
        CommandRegistry reg = new CommandRegistry();
        reg.register(stubCommand("clear"));
        reg.register(stubCommand("help"));
        reg.register(stubCommand("compact"));

        List<Command> visible = reg.visible();
        assertEquals("clear", visible.get(0).name());
        assertEquals("compact", visible.get(1).name());
        assertEquals("help", visible.get(2).name());
    }

    @Test
    void completableNames_includesAliases_sorted() {
        CommandRegistry reg = new CommandRegistry();
        reg.register(stubCommand("help", "h", "?"));
        reg.register(stubCommand("clear"));

        List<String> names = reg.completableNames();
        assertEquals(List.of("?", "clear", "h", "help"), names);
    }

    @Test
    void completableNames_excludesHidden() {
        CommandRegistry reg = new CommandRegistry();
        reg.register(stubCommand("help", "h"));
        reg.register(hiddenCommand("internal"));

        List<String> names = reg.completableNames();
        assertEquals(List.of("h", "help"), names);
    }
}
