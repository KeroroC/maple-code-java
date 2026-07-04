package com.maplecode.agent;

import com.maplecode.tool.ToolRegistry;

import java.util.List;

/**
 * 一轮 tool_use 按安全性分批：safe = 只读工具，unsafe = 有副作用工具。
 * <p>
 * 泛型 T 接受任意 tool_use 类型（AgentLoop 内部 record 或测试替身）。
 */
record Batch<T extends NamedToolUse>(List<T> safe, List<T> unsafe) {

    static <T extends NamedToolUse> Batch<T> partition(List<T> uses, ToolRegistry registry) {
        var safe = uses.stream()
            .filter(u -> registry.isReadOnly(u.name()))
            .toList();
        var unsafe = uses.stream()
            .filter(u -> !registry.isReadOnly(u.name()))
            .toList();
        return new Batch<>(safe, unsafe);
    }
}

/** tool_use 最小契约：至少有个 name 用于分批。 */
interface NamedToolUse {
    String name();
}
