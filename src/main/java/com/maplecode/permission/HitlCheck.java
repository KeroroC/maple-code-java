package com.maplecode.permission;

import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Optional;
import java.util.Set;

public final class HitlCheck implements PermissionCheck {

    /** 只读工具：沙箱已防护路径越界，DEFAULT 模式下自动放行不弹 prompt。 */
    private static final Set<String> READ_ONLY = Set.of("read_file", "glob", "grep");

    private final InputSource input;
    private final OutputSink output;
    private PermissionEngine engine;

    public HitlCheck(InputSource input, OutputSink output) {
        this.input = input;
        this.output = output;
    }

    public void setEngine(PermissionEngine engine) {
        this.engine = engine;
    }

    @Override
    public Optional<Decision> check(PermissionRequest req, PermissionContext ctx) {
        String pattern = extractPattern(req);
        if (pattern != null) {
            ToolCall tc = new ToolCall(req.toolName(), pattern);
            if (ctx.sessionAllows().contains(tc))
                return Optional.of(Decision.allow("会话级允许"));
            if (ctx.sessionDenies().contains(tc))
                return Optional.of(Decision.deny("会话级拒绝"));
        }

        // DEFAULT 模式下只读工具自动放行（沙箱已在上游拦截越界路径）
        if (READ_ONLY.contains(req.toolName())) {
            return Optional.of(Decision.allow("只读工具自动放行"));
        }

        output.println("");
        output.println("─── 需要权限确认 ─────────────────────────────────────────────");
        output.println("  工具:    " + req.toolName());
        output.println("  参数:    " + summarizeArgs(req));
        output.println("  模式:    " + ctx.mode());
        output.println("");
        output.println("  [1] 本次允许");
        output.println("  [2] 本会话允许");
        output.println("  [3] 本项目允许（写入 permissions.local.yaml）");
        output.println("  [4] 拒绝");
        output.println("");
        String choice;
        try {
            choice = input.readLine("  请选择 [1-4]: ").trim();
        } catch (RuntimeException e) {
            return Optional.of(Decision.deny("用户在权限确认时中断"));
        }

        return switch (choice) {
            case "1" -> Optional.of(Decision.allow("用户本次允许"));
            case "2" -> {
                if (pattern != null && engine != null) {
                    engine.permitForSession(new ToolCall(req.toolName(), pattern));
                }
                yield Optional.of(Decision.allow("用户本会话允许"));
            }
            case "3" -> {
                if (pattern != null && engine != null) {
                    try {
                        Path cwd = Paths.get(System.getProperty("user.dir"));
                        engine.persistProjectAllow(cwd, req.toolName(), pattern);
                    } catch (IOException e) {
                        output.println("  警告: 写入项目规则失败: "
                            + e.getMessage() + "，降级为本会话允许");
                        engine.permitForSession(new ToolCall(req.toolName(), pattern));
                    }
                }
                yield Optional.of(Decision.allow("用户本项目允许"));
            }
            case "4" -> Optional.of(Decision.deny("用户拒绝"));
            default  -> Optional.of(Decision.deny("无效选项 '" + choice + "'，默认拒绝"));
        };
    }

    static String extractPattern(PermissionRequest req) {
        return RuleCheck.extractPattern(req);
    }

    private static String summarizeArgs(PermissionRequest req) {
        return switch (req.toolName()) {
            case "exec" -> req.args().path("command").asText();
            case "read_file", "write_file", "edit_file" -> req.args().path("path").asText();
            case "glob" -> req.args().path("pattern").asText();
            case "grep" -> "pattern=" + req.args().path("pattern").asText()
                + " path=" + (req.args().has("path") ? req.args().get("path").asText() : ".");
            default -> req.args().toString();
        };
    }
}
