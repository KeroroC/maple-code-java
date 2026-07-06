package com.maplecode.permission;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;

public final class PermissionEngine {

    private final List<PermissionCheck> checks;
    private final Set<ToolCall> sessionAllow = ConcurrentHashMap.newKeySet();
    private final Set<ToolCall> sessionDeny = ConcurrentHashMap.newKeySet();
    private final AtomicReference<PermissionMode> mode = new AtomicReference<>();

    public PermissionEngine(List<PermissionCheck> checks, PermissionMode defaultMode) {
        this.checks = List.copyOf(checks);
        this.mode.set(defaultMode);
    }

    public Decision check(PermissionRequest req) {
        var ctx = new PermissionContext(mode.get(), sessionAllow, sessionDeny);
        for (PermissionCheck c : checks) {
            Optional<Decision> d = c.check(req, ctx);
            if (d.isPresent()) return d.get();
        }
        return Decision.deny("未达成决策");
    }

    public PermissionMode mode() { return mode.get(); }
    public void setMode(PermissionMode m) { mode.set(m); }

    // --- Task 8: session allow/deny ---

    public void permitForSession(ToolCall tc) { sessionAllow.add(tc); }
    public void denyForSession(ToolCall tc)    { sessionDeny.add(tc); }

    // --- Task 8: persist project allow ---

    public void persistProjectAllow(Path projectRoot, String tool, String pattern) throws IOException {
        Path file = projectRoot.resolve(".maplecode/permissions.local.yaml");
        Files.createDirectories(file.getParent());
        String entry = String.format(
            "  - tool: %s%n    pattern: %s%n    action: allow%n",
            tool, escapeYamlString(pattern));
        if (!Files.exists(file)) {
            Files.writeString(file, "rules:\n" + entry);
        } else {
            Files.writeString(file, entry, StandardOpenOption.APPEND);
        }
    }

    public static String escapeYamlString(String s) {
        return "\"" + s.replace("\\", "\\\\")
                       .replace("\"", "\\\"")
                       .replace("\n", "\\n")
                       .replace("\r", "\\r") + "\"";
    }

    // --- test-only accessors ---
    Set<ToolCall> sessionAllowForTest() { return sessionAllow; }
    Set<ToolCall> sessionDenyForTest()  { return sessionDeny; }
    public static String escapeYamlStringForTest(String s) { return escapeYamlString(s); }
}
