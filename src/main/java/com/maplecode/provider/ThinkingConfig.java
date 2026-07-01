package com.maplecode.provider;

import com.maplecode.error.ConfigException;

public record ThinkingConfig(
    Type type,
    Integer budgetTokens,        // only when type=ENABLED; >= 1024 and < max_tokens
    Effort effort                // only when type=ADAPTIVE
) {
    public enum Type { ADAPTIVE, ENABLED }
    public enum Effort { LOW, MEDIUM, HIGH }

    public ThinkingConfig {
        if (type == Type.ENABLED) {
            if (budgetTokens == null || budgetTokens < 1024) {
                throw new ConfigException(
                    "extended_thinking.type=enabled requires budget_tokens >= 1024");
            }
            if (effort != null) {
                throw new ConfigException(
                    "extended_thinking.type=enabled and effort are mutually exclusive");
            }
        }
        if (type == Type.ADAPTIVE) {
            if (effort == null) {
                throw new ConfigException(
                    "extended_thinking.type=adaptive requires effort (low|medium|high)");
            }
            if (budgetTokens != null) {
                throw new ConfigException(
                    "extended_thinking.type=adaptive and budget_tokens are mutually exclusive");
            }
        }
    }
}