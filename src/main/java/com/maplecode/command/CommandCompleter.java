package com.maplecode.command;

import org.jline.reader.Candidate;
import org.jline.reader.Completer;
import org.jline.reader.LineReader;
import org.jline.reader.ParsedLine;

import java.util.List;

/**
 * JLine Completer 实现，Tab 补全仅在行首触发。
 */
public class CommandCompleter implements Completer {
    private final CommandRegistry registry;

    public CommandCompleter(CommandRegistry registry) {
        this.registry = registry;
    }

    @Override
    public void complete(LineReader reader, ParsedLine line, List<Candidate> candidates) {
        String buffer = line.line();

        // 只在行首的 / 开头触发
        if (!buffer.startsWith("/")) return;

        // 光标必须在第一个 word 上（命令名本身），不能在参数区域
        if (line.wordIndex() > 0) return;

        String partial = line.word().toLowerCase();
        for (String name : registry.completableNames()) {
            if (("/" + name).startsWith(partial)) {
                candidates.add(new Candidate("/" + name));
            }
        }
    }
}
