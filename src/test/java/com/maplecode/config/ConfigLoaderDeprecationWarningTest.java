package com.maplecode.config;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ConfigLoaderDeprecationWarningTest {

    @Test
    void enabled_thinking_emits_deprecation_warning_to_stderr(@TempDir Path tmp) throws IOException {
        Files.writeString(tmp.resolve("config.yaml"), """
            protocol: anthropic
            model: claude-sonnet-4-6
            base_url: https://api.anthropic.com
            api_key: sk-test
            extended_thinking:
              type: enabled
              budget_tokens: 10000
            """);
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        PrintStream original = System.err;
        try {
            System.setErr(new PrintStream(err));
            ConfigLoader.load(tmp.resolve("config.yaml"));
        } finally {
            System.setErr(original);
        }
        String output = err.toString();
        assertTrue(output.contains("deprecated"),
            "expected deprecation warning, got stderr: " + output);
        assertTrue(output.contains("type=adaptive"),
            "warning should suggest migration: " + output);
    }

    @Test
    void adaptive_thinking_emits_no_deprecation_warning(@TempDir Path tmp) throws IOException {
        Files.writeString(tmp.resolve("config.yaml"), """
            protocol: anthropic
            model: claude-sonnet-4-6
            base_url: https://api.anthropic.com
            api_key: sk-test
            extended_thinking:
              type: adaptive
              effort: high
            """);
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        PrintStream original = System.err;
        try {
            System.setErr(new PrintStream(err));
            ConfigLoader.load(tmp.resolve("config.yaml"));
        } finally {
            System.setErr(original);
        }
        assertFalse(err.toString().contains("deprecated"),
            "adaptive should not warn, got: " + err);
    }
}
