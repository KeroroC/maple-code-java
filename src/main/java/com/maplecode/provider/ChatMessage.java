package com.maplecode.provider;

public record ChatMessage(Role role, String content) {
    public enum Role { USER, ASSISTANT }
}