package com.maplecode.provider;

import com.maplecode.config.AppConfig;
import com.maplecode.error.ConfigException;
import com.maplecode.provider.anthropic.AnthropicProvider;
import com.maplecode.provider.openai.OpenAiProvider;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ProviderRegistryTest {

    private AppConfig cfg(String protocol) {
        return new AppConfig(protocol, "m", "https://x", "k", null, List.of(), null,
            new AppConfig.Timeouts(10, 60), com.maplecode.permission.PermissionMode.DEFAULT,
            AppConfig.AgentLimits.defaults(), null);
    }

    @Test
    void anthropic_protocol_returns_AnthropicProvider() {
        var reg = new ProviderRegistry();
        var p = reg.create(cfg("anthropic"));
        assertInstanceOf(AnthropicProvider.class, p);
    }

    @Test
    void openai_protocol_returns_OpenAiProvider() {
        var reg = new ProviderRegistry();
        var p = reg.create(cfg("openai"));
        assertInstanceOf(OpenAiProvider.class, p);
    }

    @Test
    void unknown_protocol_throws_ConfigException() {
        var reg = new ProviderRegistry();
        ConfigException ex = assertThrows(ConfigException.class,
            () -> reg.create(cfg("azure")));
        assertEquals(expectMessage("azure"), ex.getMessage());
    }

    private String expectMessage(String p) {
        return "unknown protocol: " + p + " (supported: anthropic, openai)";
    }

    @Test
    void null_protocol_throws_ConfigException() {
        var reg = new ProviderRegistry();
        assertThrows(ConfigException.class, () -> reg.create(cfg(null)));
    }
}