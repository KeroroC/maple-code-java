package com.maplecode.memory;

import com.maplecode.config.AppConfig;
import com.maplecode.config.ConfigLoader;
import com.maplecode.permission.PermissionMode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class MemoryConfigTest {

    @Test
    void defaults_whenNotConfigured() {
        var mc = new MemoryConfig(false, null, 10);
        assertFalse(mc.enabled());
        assertNull(mc.memoryModel());
        assertEquals(10, mc.maxContextMessages());
    }

    @Test
    void disabled_returnsDisabledInstance() {
        var mc = MemoryConfig.disabled();
        assertFalse(mc.enabled());
        assertNull(mc.memoryModel());
        assertEquals(10, mc.maxContextMessages());
    }

    @Test
    void rejectsZeroMaxContextMessages() {
        assertThrows(IllegalArgumentException.class,
            () -> new MemoryConfig(true, null, 0));
    }

    @Test
    void rejectsNegativeMaxContextMessages() {
        assertThrows(IllegalArgumentException.class,
            () -> new MemoryConfig(true, null, -1));
    }

    @Test
    void fromAppConfig_returnsDisabled_whenMemoryConfigIsNull() {
        var app = minimalApp(null);
        var mc = MemoryConfig.fromAppConfig(app);
        assertFalse(mc.enabled());
    }

    @Test
    void fromAppConfig_delegates_to_MemoryConfig(@TempDir Path tmp) throws IOException {
        Files.writeString(tmp.resolve("c.yaml"), """
            protocol: anthropic
            model: claude-sonnet-4-6
            base_url: https://api.anthropic.com
            api_key: sk-test
            memory:
              enabled: true
              memory_model: claude-haiku-4-5
              max_context_messages: 20
            """);
        AppConfig cfg = ConfigLoader.load(tmp.resolve("c.yaml"));
        assertNotNull(cfg.memoryConfig());
        assertTrue(cfg.memoryConfig().enabled());
        assertEquals("claude-haiku-4-5", cfg.memoryConfig().memoryModel());
        assertEquals(20, cfg.memoryConfig().maxContextMessages());
    }

    @Test
    void fromAppConfig_defaults_maxContextMessages(@TempDir Path tmp) throws IOException {
        Files.writeString(tmp.resolve("c.yaml"), """
            protocol: anthropic
            model: claude-sonnet-4-6
            base_url: https://api.anthropic.com
            api_key: sk-test
            memory:
              enabled: true
            """);
        AppConfig cfg = ConfigLoader.load(tmp.resolve("c.yaml"));
        assertEquals(10, cfg.memoryConfig().maxContextMessages());
        assertNull(cfg.memoryConfig().memoryModel());
    }

    private static AppConfig minimalApp(MemoryConfig mc) {
        return new AppConfig("anthropic", "m", "https://api.anthropic.com", "k",
            null, java.util.List.of(), null,
            new AppConfig.Timeouts(10, 60), PermissionMode.DEFAULT,
            AppConfig.AgentLimits.defaults(), null, 0, null, mc);
    }
}
