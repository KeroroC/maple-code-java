package com.maplecode.tool;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

public final class ToolRegistry {

    private final List<Tool> tools;
    private final Map<String, Tool> byName;

    public ToolRegistry(List<Tool> tools) {
        this.tools = List.copyOf(tools);
        this.byName = new HashMap<>();
        for (var t : this.tools) {
            byName.put(t.name(), t);
        }
    }

    public List<Tool> all() {
        return tools;
    }

    public Optional<Tool> get(String name) {
        return Optional.ofNullable(byName.get(name));
    }
}