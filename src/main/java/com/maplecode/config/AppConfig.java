package com.maplecode.config;

import com.maplecode.provider.ThinkingConfig;

import java.time.Duration;

public record AppConfig(
    String protocol,
    String model,
    String baseUrl,
    String apiKey,
    String systemPrompt,           // nullable
    ThinkingConfig thinking,       // nullable
    Timeouts timeouts
) {
    public record Timeouts(int connectSeconds, int readSeconds) {
        public Duration connectDuration() { return Duration.ofSeconds(connectSeconds); }
        public Duration readDuration() { return Duration.ofSeconds(readSeconds); }
    }
}
