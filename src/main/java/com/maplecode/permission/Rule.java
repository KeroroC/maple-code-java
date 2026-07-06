package com.maplecode.permission;

public record Rule(String toolName, String pattern, Action action) {
    public enum Action { ALLOW, DENY }
}
