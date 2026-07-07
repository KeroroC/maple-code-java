package com.maplecode.permission;

import com.fasterxml.jackson.databind.JsonNode;
import java.io.IOException;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.util.Optional;
import java.util.Set;

/**
 * 路径沙箱：拒绝解析后路径逃逸沙箱根目录的文件系统工具。
 * <ul>
 *   <li>{@code read_file}、{@code write_file}、{@code edit_file}：通过
 *       {@link Path#toRealPath()} 解析 symlink 后再做前缀检查。</li>
 *   <li>{@code glob} / {@code grep}：仅使用 {@link Path#normalize()}（pattern 不是真实
 *       路径）；逃逸则拒绝。</li>
 *   <li>{@code exec}：完全跳过（不是路径工具）。</li>
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

        // glob/grep pattern：使用 normalize（pattern 不是真实文件）
        if (req.toolName().equals("glob") || req.toolName().equals("grep")) {
            Path normalized = requested.normalize();
            if (!normalized.startsWith(sandboxRoot)) {
                return Optional.of(Decision.deny(
                    "路径越界: " + normalized + " 在沙箱 " + sandboxRoot + " 之外"));
            }
            return Optional.empty();
        }

        // read_file / write_file / edit_file：解析 symlink 后做前缀检查
        Path real;
        try {
            real = requested.toRealPath();
        } catch (NoSuchFileException e) {
            return Optional.empty();  // 路径不存在——不在沙箱管辖范围
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
