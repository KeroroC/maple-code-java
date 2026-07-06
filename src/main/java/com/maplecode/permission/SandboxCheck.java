package com.maplecode.permission;

import com.fasterxml.jackson.databind.JsonNode;
import java.io.IOException;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.util.Optional;
import java.util.Set;

/**
 * Path sandbox: denies file-system tools whose resolved path escapes the sandbox root.
 * <ul>
 *   <li>{@code read_file}, {@code write_file}, {@code edit_file}: symlink-resolved via
 *       {@link Path#toRealPath()} before prefix check.</li>
 *   <li>{@code glob} / {@code grep}: only {@link Path#normalize()} (patterns are not real
 *       paths); escapes are denied.</li>
 *   <li>{@code exec}: skipped entirely (not a path tool).</li>
 * </ul>
 */
public final class SandboxCheck implements PermissionCheck {

    private static final Set<String> PATH_TOOLS = Set.of(
        "read_file", "write_file", "edit_file", "glob", "grep");

    private final Path sandboxRoot;

    public SandboxCheck(Path cwd) {
        try {
            this.sandboxRoot = cwd.toRealPath();
        } catch (IOException e) {
            throw new IllegalStateException("无法解析沙箱根目录: " + cwd, e);
        }
    }

    @Override
    public Optional<Decision> check(PermissionRequest req, PermissionContext ctx) {
        if (!PATH_TOOLS.contains(req.toolName())) return Optional.empty();

        JsonNode pathNode = switch (req.toolName()) {
            case "glob" -> req.args().get("pattern");
            case "grep" -> req.args().has("path") ? req.args().get("path") : null;
            default     -> req.args().get("path");
        };
        if (pathNode == null || pathNode.isNull()) return Optional.empty();

        String raw = pathNode.asText();
        Path requested = raw.startsWith("/") ? Path.of(raw) : req.cwd().resolve(raw);

        // glob/grep pattern: use normalize (patterns aren't real files)
        if (req.toolName().equals("glob") || req.toolName().equals("grep")) {
            Path normalized = requested.normalize();
            if (!normalized.startsWith(sandboxRoot)) {
                return Optional.of(Decision.deny(
                    "路径越界: " + normalized + " 在沙箱 " + sandboxRoot + " 之外"));
            }
            return Optional.empty();
        }

        // read_file / write_file / edit_file: resolve symlinks then prefix-check
        Path real;
        try {
            real = requested.toRealPath();
        } catch (NoSuchFileException e) {
            return Optional.empty();  // path doesn't exist -- not sandbox's concern
        } catch (IOException e) {
            return Optional.of(Decision.deny("无法解析路径: " + e.getMessage()));
        }
        if (!real.startsWith(sandboxRoot)) {
            return Optional.of(Decision.deny(
                "路径越界: " + real + " 在沙箱 " + sandboxRoot + " 之外"));
        }
        return Optional.empty();
    }
}
