package com.maplecode.command;

import org.jline.reader.Candidate;
import org.jline.reader.LineReader;
import org.jline.reader.ParsedLine;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

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

    @Test
    void complete_matchesCommandsWithSlash() {
        CommandRegistry reg = new CommandRegistry();
        reg.register(new ExitCommand());
        reg.register(new HelpCommand(reg));
        CommandCompleter completer = new CommandCompleter(reg);

        // 模拟输入 "/he"
        ParsedLine parsedLine = mock(ParsedLine.class);
        when(parsedLine.line()).thenReturn("/he");
        when(parsedLine.word()).thenReturn("/he");
        when(parsedLine.wordIndex()).thenReturn(0);

        List<Candidate> candidates = new ArrayList<>();
        completer.complete(mock(LineReader.class), parsedLine, candidates);

        // 应该匹配 "/help"（因为 "/help".startsWith("/he") = true）
        // 不应该匹配 "/h"（因为 "/h".startsWith("/he") = false）
        assertTrue(candidates.stream().anyMatch(c -> c.value().equals("/help")),
            "Should match /help for input '/he'");
        assertFalse(candidates.stream().anyMatch(c -> c.value().equals("/h")),
            "Should not match /h for input '/he'");
    }

    @Test
    void complete_matchesExactCommand() {
        CommandRegistry reg = new CommandRegistry();
        reg.register(new ExitCommand());
        reg.register(new HelpCommand(reg));
        CommandCompleter completer = new CommandCompleter(reg);

        // 模拟输入 "/exit"
        ParsedLine parsedLine = mock(ParsedLine.class);
        when(parsedLine.line()).thenReturn("/exit");
        when(parsedLine.word()).thenReturn("/exit");
        when(parsedLine.wordIndex()).thenReturn(0);

        List<Candidate> candidates = new ArrayList<>();
        completer.complete(mock(LineReader.class), parsedLine, candidates);

        // 应该匹配 "/exit"
        assertTrue(candidates.stream().anyMatch(c -> c.value().equals("/exit")),
            "Should match /exit for input '/exit'");
    }

    @Test
    void complete_noMatchWithoutSlash() {
        CommandRegistry reg = new CommandRegistry();
        reg.register(new ExitCommand());
        reg.register(new HelpCommand(reg));
        CommandCompleter completer = new CommandCompleter(reg);

        // 模拟输入 "he"（没有前导斜杠）
        ParsedLine parsedLine = mock(ParsedLine.class);
        when(parsedLine.line()).thenReturn("he");
        when(parsedLine.word()).thenReturn("he");
        when(parsedLine.wordIndex()).thenReturn(0);

        List<Candidate> candidates = new ArrayList<>();
        completer.complete(mock(LineReader.class), parsedLine, candidates);

        // 不应该匹配任何命令
        assertTrue(candidates.isEmpty(), "Should not match without leading slash");
    }

    @Test
    void complete_noMatchInArguments() {
        CommandRegistry reg = new CommandRegistry();
        reg.register(new ExitCommand());
        reg.register(new HelpCommand(reg));
        CommandCompleter completer = new CommandCompleter(reg);

        // 模拟输入 "/help he"（光标在第二个单词）
        ParsedLine parsedLine = mock(ParsedLine.class);
        when(parsedLine.line()).thenReturn("/help he");
        when(parsedLine.word()).thenReturn("he");
        when(parsedLine.wordIndex()).thenReturn(1);

        List<Candidate> candidates = new ArrayList<>();
        completer.complete(mock(LineReader.class), parsedLine, candidates);

        // 不应该匹配任何命令（因为光标不在第一个单词）
        assertTrue(candidates.isEmpty(), "Should not match when cursor is in arguments");
    }
}
