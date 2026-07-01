package com.maplecode.provider;

import com.maplecode.config.AppConfig;
import com.maplecode.error.ConfigException;
import com.maplecode.provider.anthropic.AnthropicProvider;
import com.maplecode.provider.openai.OpenAiProvider;

import java.util.List;
import java.util.Map;
import java.util.function.Function;

public final class ProviderRegistry {

    private final Map<String, Function<AppConfig, LlmProvider>> factories = Map.of(
        "anthropic", AnthropicProvider::new,
        "openai",    OpenAiProvider::new
    );

    public LlmProvider create(AppConfig config) {
        if (config.protocol() == null) {
            throw new ConfigException("missing required field: protocol");
        }
        Function<AppConfig, LlmProvider> factory = factories.get(config.protocol());
        if (factory == null) {
            throw new ConfigException("unknown protocol: " + config.protocol()
                + " (supported: " + String.join(", ", SUPPORTED) + ")");
        }
        return factory.apply(config);
    }

    private static final List<String> SUPPORTED = List.of("anthropic", "openai");
}