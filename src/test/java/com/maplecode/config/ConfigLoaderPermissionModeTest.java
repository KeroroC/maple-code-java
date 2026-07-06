package com.maplecode.config;

import com.maplecode.error.ConfigException;
import com.maplecode.permission.PermissionMode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class ConfigLoaderPermissionModeTest {

    @Test
    void default_when_missing(@TempDir Path tmp) throws Exception {
        var f = tmp.resolve("c.yaml");
        Files.writeString(f, """
            protocol: anthropic
            model: m
            base_url: https://x
            api_key: k
            """);
        assertEquals(PermissionMode.DEFAULT, ConfigLoader.load(f).permissionMode());
    }

    @Test
    void explicit_strict(@TempDir Path tmp) throws Exception {
        var f = tmp.resolve("c.yaml");
        Files.writeString(f, """
            protocol: anthropic
            model: m
            base_url: https://x
            api_key: k
            permission_mode: strict
            """);
        assertEquals(PermissionMode.STRICT, ConfigLoader.load(f).permissionMode());
    }

    @Test
    void explicit_permissive(@TempDir Path tmp) throws Exception {
        var f = tmp.resolve("c.yaml");
        Files.writeString(f, """
            protocol: anthropic
            model: m
            base_url: https://x
            api_key: k
            permission_mode: permissive
            """);
        assertEquals(PermissionMode.PERMISSIVE, ConfigLoader.load(f).permissionMode());
    }

    @Test
    void invalid_value_throws(@TempDir Path tmp) throws Exception {
        var f = tmp.resolve("c.yaml");
        Files.writeString(f, """
            protocol: anthropic
            model: m
            base_url: https://x
            api_key: k
            permission_mode: chaos
            """);
        assertThrows(ConfigException.class, () -> ConfigLoader.load(f));
    }

    @Test
    void agent_limits_defaults(@TempDir Path tmp) throws Exception {
        var f = tmp.resolve("c.yaml");
        Files.writeString(f, """
            protocol: anthropic
            model: m
            base_url: https://x
            api_key: k
            """);
        var limits = ConfigLoader.load(f).agentLimits();
        assertEquals(50, limits.maxIterations());
        assertEquals(3, limits.maxConsecutiveUnknown());
    }

    @Test
    void agent_limits_custom(@TempDir Path tmp) throws Exception {
        var f = tmp.resolve("c.yaml");
        Files.writeString(f, """
            protocol: anthropic
            model: m
            base_url: https://x
            api_key: k
            agent:
              max_iterations: 100
              max_consecutive_unknown: 5
            """);
        var limits = ConfigLoader.load(f).agentLimits();
        assertEquals(100, limits.maxIterations());
        assertEquals(5, limits.maxConsecutiveUnknown());
    }

    @Test
    void agent_limits_partial(@TempDir Path tmp) throws Exception {
        var f = tmp.resolve("c.yaml");
        Files.writeString(f, """
            protocol: anthropic
            model: m
            base_url: https://x
            api_key: k
            agent:
              max_iterations: 80
            """);
        var limits = ConfigLoader.load(f).agentLimits();
        assertEquals(80, limits.maxIterations());
        assertEquals(3, limits.maxConsecutiveUnknown());  // 未指定用默认
    }
}
