package com.maplecode.command;

import java.util.*;

/**
 * 命令注册中心。启动时注册所有命令，检测名称/别名冲突。
 */
public final class CommandRegistry {
    private final Map<String, Command> byName = new HashMap<>();
    private final Map<String, Command> byAlias = new HashMap<>();

    /**
     * 注册一个命令。冲突时抛 IllegalArgumentException。
     */
    public void register(Command command) {
        String lowerName = command.name().toLowerCase();

        if (byName.containsKey(lowerName)) {
            throw new IllegalArgumentException("duplicate command name: " + lowerName);
        }
        if (byAlias.containsKey(lowerName)) {
            throw new IllegalArgumentException("command name conflicts with alias: " + lowerName);
        }

        byName.put(lowerName, command);

        for (String alias : command.aliases()) {
            String lowerAlias = alias.toLowerCase();
            if (lowerAlias.equals(lowerName)) {
                throw new IllegalArgumentException("alias equals own name: " + lowerAlias);
            }
            if (byName.containsKey(lowerAlias)) {
                throw new IllegalArgumentException("alias conflicts with command name: " + lowerAlias);
            }
            if (byAlias.containsKey(lowerAlias)) {
                throw new IllegalArgumentException("duplicate alias: " + lowerAlias);
            }
            byAlias.put(lowerAlias, command);
        }
    }

    /** 按名称或别名查找（大小写不敏感），未命中返回 empty。 */
    public Optional<Command> lookup(String name) {
        String lower = name.toLowerCase();
        Command cmd = byName.get(lower);
        if (cmd != null) return Optional.of(cmd);
        return Optional.ofNullable(byAlias.get(lower));
    }

    /** 所有非隐藏命令，按 name 字母序排列。 */
    public List<Command> visible() {
        List<Command> result = new ArrayList<>();
        for (Command cmd : byName.values()) {
            if (!cmd.hidden()) {
                result.add(cmd);
            }
        }
        result.sort(Comparator.comparing(Command::name));
        return result;
    }

    /** 所有命令名 + 别名（非隐藏），按字母序排列。用于 Tab 补全。 */
    public List<String> completableNames() {
        List<String> names = new ArrayList<>();
        for (Command cmd : byName.values()) {
            if (!cmd.hidden()) {
                names.add(cmd.name());
                names.addAll(cmd.aliases());
            }
        }
        Collections.sort(names);
        return names;
    }
}
