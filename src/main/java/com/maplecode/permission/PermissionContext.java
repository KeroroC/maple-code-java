package com.maplecode.permission;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

public final class PermissionContext {

    private final PermissionMode mode;
    private final Set<ToolCall> sessionAllow;
    private final Set<ToolCall> sessionDeny;

    public PermissionContext(PermissionMode mode) {
        this(mode, ConcurrentHashMap.newKeySet(), ConcurrentHashMap.newKeySet());
    }

    public PermissionContext(PermissionMode mode,
                              Set<ToolCall> sessionAllow,
                              Set<ToolCall> sessionDeny) {
        this.mode = mode;
        this.sessionAllow = sessionAllow;
        this.sessionDeny = sessionDeny;
    }

    public PermissionMode mode() { return mode; }
    public Set<ToolCall> sessionAllows() { return sessionAllow; }
    public Set<ToolCall> sessionDenies() { return sessionDeny; }
}
