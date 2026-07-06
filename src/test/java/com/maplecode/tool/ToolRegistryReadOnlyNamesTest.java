package com.maplecode.tool;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class ToolRegistryReadOnlyNamesTest {

    @Test
    void defaultConstructorKeepsBuiltinReadOnlyNames() {
        var reg = new ToolRegistry(List.of());
        assertTrue(reg.isReadOnly("read_file"));
        assertTrue(reg.isReadOnly("glob"));
        assertTrue(reg.isReadOnly("grep"));
        assertFalse(reg.isReadOnly("exec"));
        assertFalse(reg.isReadOnly("mcp__gh__create_issue"));
    }

    @Test
    void customReadOnlyNamesOnlyMatchesThose() {
        var reg = new ToolRegistry(List.of(),
            Set.of("read_file", "mcp__gh__list"));  // 自定义集合
        assertTrue(reg.isReadOnly("read_file"));
        assertTrue(reg.isReadOnly("mcp__gh__list"));
        assertFalse(reg.isReadOnly("glob"));   // 不再默认算
        assertFalse(reg.isReadOnly("grep"));   // 不再默认算
    }
}
