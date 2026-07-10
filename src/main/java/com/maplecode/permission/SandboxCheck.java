package com.maplecode.permission;

import com.fasterxml.jackson.databind.JsonNode;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.util.Optional;
import java.util.Set;

/**
 * 路径沙箱：拒绝解析后路径逃逸沙箱根目录的文件系统工具。
 * <ul>
 *   <li>{@code read_file}、{@code write_file}、{@code edit_file}：通过
 *       {@link Path#toRealPath()} 解析 symlink 后再做前缀检查。文件不存在时，
 *       从最近存在的父目录开始解析真实路径，再将剩余相对路径拼回并检查边界。</li>
 *   <li>{@code grep}：搜索根目录使用 {@link Path#toRealPath()} 解析 symlink；
 *       {@code glob} 的 pattern 不是真实路径，仅用 {@link Path#normalize()}。</li>
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

    /**
     * 从目标路径开始，逐级向上查找存在的父目录，解析其真实路径，
     * 再将剩余相对路径拼回。用于文件不存在时防止通过 symlink 父目录逃逸。
     *
     * @param target 目标路径
     * @return 解析后的真实路径，如果连根目录都不存在则返回 null
     */
    private Path resolveExistingParent(Path target) {
        // 逐级向上查找存在的目录
        Path current = target;
        Path relative = null;
        while (current != null) {
            if (Files.exists(current)) {
                try {
                    Path real = current.toRealPath();
                    // 如果有剩余的相对路径，拼回去
                    return relative != null ? real.resolve(relative) : real;
                } catch (IOException e) {
                    return null;
                }
            }
            // 向上一级
            Path parent = current.getParent();
            if (parent == null) {
                // 到达根目录仍然不存在
                return null;
            }
            // 记录剩余的相对路径
            relative = relative == null
                ? current.getFileName()
                : current.getFileName().resolve(relative);
            current = parent;
        }
        return null;
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

        // glob pattern：使用 normalize（pattern 不是真实文件）
        if (req.toolName().equals("glob")) {
            Path normalized = requested.normalize();
            if (!normalized.startsWith(sandboxRoot)) {
                return Optional.of(Decision.deny(
                    "路径越界: " + normalized + " 在沙箱 " + sandboxRoot + " 之外"));
            }
            return Optional.empty();
        }

        // grep：搜索根目录是真实路径，必须解析 symlink
        if (req.toolName().equals("grep")) {
            Path real;
            try {
                real = requested.toRealPath();
            } catch (NoSuchFileException e) {
                // 搜索根目录不存在，工具层会报错，不在沙箱管辖范围
                return Optional.empty();
            } catch (IOException e) {
                return Optional.of(Decision.deny("无法解析路径: " + e.getMessage()));
            }
            if (!real.startsWith(sandboxRoot)) {
                return Optional.of(Decision.deny(
                    "路径越界: " + real + " 在沙箱 " + sandboxRoot + " 之外"));
            }
            return Optional.empty();
        }

        // read_file / write_file / edit_file：解析 symlink 后做前缀检查
        Path real;
        try {
            real = requested.toRealPath();
        } catch (NoSuchFileException e) {
            // 文件不存在时，从最近存在的父目录开始解析真实路径，
            // 再将剩余相对路径拼回并检查边界
            real = resolveExistingParent(requested);
            if (real == null) {
                // 连根目录都不存在，工具层会报错
                return Optional.empty();
            }
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
