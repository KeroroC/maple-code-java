package com.maplecode.permission;

import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Optional;

public final class HitlCheck implements PermissionCheck {

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
                return Optional.of(Decision.allow("session allow"));
            if (ctx.sessionDenies().contains(tc))
                return Optional.of(Decision.deny("session deny"));
        }

        output.println("");
        output.println("─── permission required ─────────────────────────────────────");
        output.println("  tool:    " + req.toolName());
        output.println("  args:    " + summarizeArgs(req));
        output.println("  mode:    " + ctx.mode());
        output.println("");
        output.println("  [1] allow this time");
        output.println("  [2] allow for this session");
        output.println("  [3] allow for this project (writes permissions.local.yaml)");
        output.println("  [4] deny");
        output.println("");
        String choice;
        try {
            choice = input.readLine("  choice [1-4]: ").trim();
        } catch (RuntimeException e) {
            return Optional.of(Decision.deny("user interrupted at permission prompt"));
        }

        return switch (choice) {
            case "1" -> Optional.of(Decision.allow("user allowed once"));
            case "2" -> {
                if (pattern != null && engine != null) {
                    engine.permitForSession(new ToolCall(req.toolName(), pattern));
                }
                yield Optional.of(Decision.allow("user allowed for session"));
            }
            case "3" -> {
                if (pattern != null && engine != null) {
                    try {
                        Path cwd = Paths.get(System.getProperty("user.dir"));
                        engine.persistProjectAllow(cwd, req.toolName(), pattern);
                    } catch (IOException e) {
                        output.println("  warning: failed to persist project allow: "
                            + e.getMessage() + "; treating as session allow");
                        engine.permitForSession(new ToolCall(req.toolName(), pattern));
                    }
                }
                yield Optional.of(Decision.allow("user allowed for project"));
            }
            case "4" -> Optional.of(Decision.deny("user denied"));
            default  -> Optional.of(Decision.deny("invalid choice '" + choice + "', denying"));
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
