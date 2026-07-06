package com.maplecode.permission;

import java.util.Optional;

public final class ModeCheck implements PermissionCheck {
    @Override
    public Optional<Decision> check(PermissionRequest req, PermissionContext ctx) {
        return switch (ctx.mode()) {
            case STRICT     -> Optional.of(Decision.deny("无匹配规则且模式为严格"));
            case PERMISSIVE -> Optional.of(Decision.allow("无匹配规则且模式为放行"));
            case DEFAULT    -> Optional.empty();
        };
    }
}
