package com.maplecode.mcp.config;

import com.maplecode.error.ConfigException;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class McpServerSpecTest {

    @Test
    void stdioSpec_acceptsValidFields() {
        var spec = new McpServerSpec.Stdio("gh",
            "npx", List.of("-y", "@mcp/server"),
            Map.of("TOKEN", "abc"));
        assertEquals("gh", spec.name());
        assertEquals("npx", spec.command());
    }

    @Test
    void stdioSpec_emptyCommandThrows() {
        assertThrows(ConfigException.class,
            () -> new McpServerSpec.Stdio("gh", "", List.of(), Map.of()));
    }

    @Test
    void httpSpec_acceptsValidFields() {
        var spec = new McpServerSpec.Http("notion",
            "https://mcp.example.com/mcp",
            Map.of("Authorization", "Bearer x"));
        assertEquals("https://mcp.example.com/mcp", spec.url());
    }

    @Test
    void httpSpec_nonHttpUrlThrows() {
        assertThrows(ConfigException.class,
            () -> new McpServerSpec.Http("notion", "ftp://x", Map.of()));
    }

    @Test
    void nameValidation_allowsSafeChars() {
        assertDoesNotThrow(() ->
            new McpServerSpec.Stdio("gh-1_ok", "x", List.of(), Map.of()));
    }

    @Test
    void nameValidation_rejectsBadChars() {
        assertThrows(ConfigException.class,
            () -> new McpServerSpec.Stdio("bad name!", "x", List.of(), Map.of()));
        assertThrows(ConfigException.class,
            () -> new McpServerSpec.Stdio("", "x", List.of(), Map.of()));
        assertThrows(ConfigException.class,
            () -> new McpServerSpec.Stdio("a".repeat(33), "x", List.of(), Map.of()));
    }
}
