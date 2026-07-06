package com.maplecode.mcp.config;

import com.maplecode.error.ConfigException;

import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

public sealed interface McpServerSpec
    permits McpServerSpec.Stdio, McpServerSpec.Http {

    Pattern NAME_PATTERN = Pattern.compile("[a-zA-Z0-9_-]+");

    String name();
    boolean enabled();

    record Stdio(
        String name,
        String command,
        List<String> args,
        Map<String, String> env,
        boolean enabled
    ) implements McpServerSpec {

        public Stdio {
            validateName(name);
            if (command == null || command.isBlank())
                throw new ConfigException("mcp server '" + name + "': command is required");
            args = args == null ? List.of() : List.copyOf(args);
            env = env == null ? Map.of() : Map.copyOf(env);
        }

        /** 便捷构造器，enabled 默认 true。 */
        public Stdio(String name, String command, List<String> args, Map<String, String> env) {
            this(name, command, args, env, true);
        }

        public String type() { return "stdio"; }
    }

    record Http(
        String name,
        String url,
        Map<String, String> headers,
        boolean enabled
    ) implements McpServerSpec {
        public Http {
            validateName(name);
            if (url == null || !(url.startsWith("http://") || url.startsWith("https://")))
                throw new ConfigException("mcp server '" + name + "': url must be http(s)");
            headers = headers == null ? Map.of() : Map.copyOf(headers);
        }

        /** 便捷构造器，enabled 默认 true。 */
        public Http(String name, String url, Map<String, String> headers) {
            this(name, url, headers, true);
        }

        public String type() { return "http"; }
    }

    static void validateName(String name) {
        if (name == null || name.isEmpty() || name.length() > 32)
            throw new ConfigException("mcp server name must be 1-32 chars: " + name);
        if (!NAME_PATTERN.matcher(name).matches())
            throw new ConfigException("mcp server name must match [a-zA-Z0-9_-]+: " + name);
    }
}
