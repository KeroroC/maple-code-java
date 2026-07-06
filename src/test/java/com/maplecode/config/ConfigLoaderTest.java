package com.maplecode.config;

import com.maplecode.error.ConfigException;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ConfigLoaderTest {

    @Test
    void loads_full_anthropic_config(@TempDir Path tmp) throws IOException {
        Files.writeString(tmp.resolve("config.yaml"), """
            protocol: anthropic
            model: claude-sonnet-4-6
            base_url: https://api.anthropic.com
            api_key: sk-test-key
            system_prompt: hi
            extended_thinking:
              type: adaptive
              effort: high
            timeouts:
              connect_seconds: 5
              read_seconds: 30
            """);
        AppConfig cfg = ConfigLoader.load(tmp.resolve("config.yaml"));
        assertEquals("anthropic", cfg.protocol());
        assertEquals("claude-sonnet-4-6", cfg.model());
        assertEquals("https://api.anthropic.com", cfg.baseUrl());
        assertEquals("sk-test-key", cfg.apiKey());
        assertEquals("hi", cfg.yamlPrompt());
        assertNotNull(cfg.thinking());
        assertEquals("ADAPTIVE", cfg.thinking().type().name());
        assertEquals("HIGH", cfg.thinking().effort().name());
        assertEquals(5, cfg.timeouts().connectSeconds());
        assertEquals(30, cfg.timeouts().readSeconds());
    }

    @Test
    void minimal_config_has_null_yaml_prompt(@TempDir Path tmp) throws IOException {
        Files.writeString(tmp.resolve("config.yaml"), """
            protocol: openai
            model: gpt-4o
            base_url: https://api.openai.com/v1
            api_key: sk-test
            """);
        AppConfig cfg = ConfigLoader.load(tmp.resolve("config.yaml"));
        assertEquals("openai", cfg.protocol());
        assertNull(cfg.yamlPrompt());
        assertNotNull(cfg.systemBlocks());
        assertTrue(cfg.systemBlocks().isEmpty());
        assertNull(cfg.thinking());
        assertEquals(10, cfg.timeouts().connectSeconds());   // default
        assertEquals(60, cfg.timeouts().readSeconds());      // default
    }

    @Test
    void blank_system_prompt_results_in_null_yaml_prompt(@TempDir Path tmp) throws IOException {
        Files.writeString(tmp.resolve("config.yaml"), """
            protocol: anthropic
            model: claude-sonnet-4-6
            base_url: https://api.anthropic.com
            api_key: sk-test
            system_prompt: "  "
            """);
        AppConfig cfg = ConfigLoader.load(tmp.resolve("config.yaml"));
        assertNull(cfg.yamlPrompt());   // 空白等同于 null，无 fallback 常量
    }

    @Test
    void missing_required_field_throws(@TempDir Path tmp) throws IOException {
        Files.writeString(tmp.resolve("config.yaml"), """
            protocol: anthropic
            model: claude-sonnet-4-6
            base_url: https://api.anthropic.com
            """);
        ConfigException ex = assertThrows(ConfigException.class,
            () -> ConfigLoader.load(tmp.resolve("config.yaml")));
        assertEquals("missing required field: api_key", ex.getMessage());
    }

    @Test
    void api_key_env_placeholder_replaced(@TempDir Path tmp) throws IOException {
        String home = System.getenv("HOME");
        Assumptions.assumeTrue(home != null && !home.isBlank(), "HOME env var must be set");

        Files.writeString(tmp.resolve("config.yaml"), """
            protocol: anthropic
            model: claude-sonnet-4-6
            base_url: https://api.anthropic.com
            api_key: ${HOME}
            """);
        AppConfig cfg = ConfigLoader.load(tmp.resolve("config.yaml"));
        assertEquals(home, cfg.apiKey(),
            "api_key should be replaced with the value of $HOME");
    }

    @Test
    void unknown_env_var_throws(@TempDir Path tmp) throws IOException {
        Files.writeString(tmp.resolve("config.yaml"), """
            protocol: anthropic
            model: claude-sonnet-4-6
            base_url: https://api.anthropic.com
            api_key: ${DEFINITELY_NOT_SET_12345_XYZ}
            """);
        ConfigException ex = assertThrows(ConfigException.class,
            () -> ConfigLoader.load(tmp.resolve("config.yaml")));
        assertEquals("environment variable not set: DEFINITELY_NOT_SET_12345_XYZ", ex.getMessage());
    }

    @Test
    void invalid_thinking_type_fails_through_ThinkingConfig(@TempDir Path tmp) throws IOException {
        Files.writeString(tmp.resolve("config.yaml"), """
            protocol: anthropic
            model: claude-sonnet-4-6
            base_url: https://api.anthropic.com
            api_key: sk-test
            extended_thinking:
              type: enabled
              budget_tokens: 500
            """);
        ConfigException ex = assertThrows(ConfigException.class,
            () -> ConfigLoader.load(tmp.resolve("config.yaml")));
        assertEquals("extended_thinking.type=enabled requires budget_tokens >= 1024", ex.getMessage());
    }

    @Test
    void mcp_servers_block_parsed(@TempDir Path tmp) throws IOException {
        Files.writeString(tmp.resolve("config.yaml"), """
            protocol: anthropic
            model: claude-sonnet-4-6
            base_url: https://api.anthropic.com
            api_key: sk-test
            mcp_servers:
              enabled: true
              startup_timeout_ms: 8000
            """);
        AppConfig cfg = ConfigLoader.load(tmp.resolve("config.yaml"));
        assertNotNull(cfg.mcpConfig());
        assertTrue(cfg.mcpConfig().enabled());
        assertEquals(8000, cfg.mcpConfig().startupTimeoutMs());
    }

    @Test
    void mcp_servers_disabled(@TempDir Path tmp) throws IOException {
        Files.writeString(tmp.resolve("config.yaml"), """
            protocol: anthropic
            model: claude-sonnet-4-6
            base_url: https://api.anthropic.com
            api_key: sk-test
            mcp_servers:
              enabled: false
            """);
        AppConfig cfg = ConfigLoader.load(tmp.resolve("config.yaml"));
        assertNotNull(cfg.mcpConfig());
        assertFalse(cfg.mcpConfig().enabled());
        assertEquals(5000, cfg.mcpConfig().startupTimeoutMs()); // default
    }

    @Test
    void mcp_servers_disabled_case_insensitive(@TempDir Path tmp) throws IOException {
        for (String falsy : new String[]{"\"False\"", "\"FALSE\"", "\"no\"", "\"off\"", "\"0\""}) {
            Files.writeString(tmp.resolve("config.yaml"), """
                protocol: anthropic
                model: claude-sonnet-4-6
                base_url: https://api.anthropic.com
                api_key: sk-test
                mcp_servers:
                  enabled: %s
                """.formatted(falsy));
            AppConfig cfg = ConfigLoader.load(tmp.resolve("config.yaml"));
            assertFalse(cfg.mcpConfig().enabled(),
                "enabled: " + falsy + " should be disabled");
        }
    }

    @Test
    void mcp_servers_absent_returns_null(@TempDir Path tmp) throws IOException {
        Files.writeString(tmp.resolve("config.yaml"), """
            protocol: anthropic
            model: claude-sonnet-4-6
            base_url: https://api.anthropic.com
            api_key: sk-test
            """);
        AppConfig cfg = ConfigLoader.load(tmp.resolve("config.yaml"));
        assertNull(cfg.mcpConfig());
    }
}
