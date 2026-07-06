package com.maplecode.permission;

import org.jline.reader.LineReader;
import org.jline.reader.UserInterruptException;
import org.jline.terminal.Attributes;
import org.jline.terminal.Terminal;
import org.jline.utils.InfoCmp;

import java.io.IOException;
import java.util.EnumSet;
import java.util.List;

public final class JLineInputSource implements InputSource {
    private final LineReader reader;
    private final Terminal terminal;

    public JLineInputSource(LineReader reader) {
        this.reader = reader;
        this.terminal = reader.getTerminal();
    }

    @Override
    public String readLine(String prompt) {
        try {
            return reader.readLine(prompt);
        } catch (UserInterruptException e) {
            throw new RuntimeException(e);
        }
    }

    @Override
    public int readChoice(String prompt, List<String> options) {
        int selected = 0;

        // 保存原始属性，切换到 raw mode
        Attributes original = terminal.getAttributes();
        Attributes raw = new Attributes(original);
        raw.setLocalFlags(EnumSet.of(Attributes.LocalFlag.ICANON), false);
        raw.setLocalFlags(EnumSet.of(Attributes.LocalFlag.ECHO), false);
        raw.setInputFlags(EnumSet.of(Attributes.InputFlag.ICRNL), false);
        terminal.setAttributes(raw);
        terminal.puts(InfoCmp.Capability.cursor_invisible);

        try {
            render(prompt, options, selected);
            while (true) {
                int c = readByte();
                if (c == -1) throw new RuntimeException("EOF");

                // ESC 序列（箭头键）
                if (c == 27) {
                    int next = readByte();
                    if (next == '[') {
                        int arrow = readByte();
                        if (arrow == 65) {  // 上
                            selected = (selected - 1 + options.size()) % options.size();
                        } else if (arrow == 66) {  // 下
                            selected = (selected + 1) % options.size();
                        }
                    }
                    clearRendered(options.size());
                    render(prompt, options, selected);
                    continue;
                }

                // 回车 (CR=13 或 LF=10)
                if (c == 13 || c == 10) {
                    clearRendered(options.size());
                    System.out.println(prompt);
                    System.out.println("  ✓ " + options.get(selected));
                    return selected;
                }

                // 数字快捷键
                if (c >= '1' && c <= '9') {
                    int idx = c - '1';
                    if (idx < options.size()) {
                        clearRendered(options.size());
                        System.out.println(prompt);
                        System.out.println("  ✓ " + options.get(idx));
                        return idx;
                    }
                }
            }
        } finally {
            terminal.puts(InfoCmp.Capability.cursor_visible);
            terminal.setAttributes(original);  // 恢复原始属性
        }
    }

    private int readByte() {
        try {
            return terminal.input().read();
        } catch (IOException e) {
            return -1;
        }
    }

    private void render(String prompt, List<String> options, int selected) {
        System.out.println(prompt);
        for (int i = 0; i < options.size(); i++) {
            String cursor = (i == selected) ? "▶ " : "  ";
            System.out.println(cursor + options.get(i));
        }
    }

    private void clearRendered(int optionCount) {
        for (int i = 0; i <= optionCount; i++) {
            System.out.print("\033[A\033[2K");
        }
    }
}
