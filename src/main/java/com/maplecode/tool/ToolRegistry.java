package com.maplecode.tool;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

public final class ToolRegistry {

    private static final Set<String> READ_ONLY_DEFAULT = Set.of("read_file", "glob", "grep");

    private final List<Tool> tools;
    private final Map<String, Tool> byName;
    private final Set<String> readOnlyNames;

    public ToolRegistry(List<Tool> tools) {
        this(tools, READ_ONLY_DEFAULT);
    }

    public ToolRegistry(List<Tool> tools, Set<String> readOnlyNames) {
        this.tools = List.copyOf(tools);
        this.byName = new HashMap<>();
        for (var t : this.tools) {
            if (byName.containsKey(t.name())) {
                throw new IllegalArgumentException("duplicate tool name: " + t.name());
            }
            byName.put(t.name(), t);
        }
        this.readOnlyNames = Set.copyOf(readOnlyNames);
    }

    public List<Tool> all() {
        return tools;
    }

    public Optional<Tool> get(String name) {
        return Optional.ofNullable(byName.get(name));
    }

    public boolean isReadOnly(String name) {
        return readOnlyNames.contains(name);
    }

    public List<Tool> readOnly() {
        return tools.stream().filter(t -> readOnlyNames.contains(t.name())).toList();
    }
}