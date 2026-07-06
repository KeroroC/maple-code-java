package com.maplecode.permission;

import java.util.List;

public record PermissionFile(List<RuleEntry> rules) {
    public record RuleEntry(String tool, String pattern, String action) {}
    public PermissionFile { rules = List.copyOf(rules); }
}
