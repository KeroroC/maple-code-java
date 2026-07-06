package com.maplecode.permission;

import java.util.Optional;

public final class ModeCheck implements PermissionCheck {
    @Override
    public Optional<Decision> check(PermissionRequest req, PermissionContext ctx) {
        return switch (ctx.mode()) {
            case STRICT     -> Optional.of(Decision.deny("no matching rule and mode is strict"));
            case PERMISSIVE -> Optional.of(Decision.allow("no matching rule and mode is permissive"));
            case DEFAULT    -> Optional.empty();
        };
    }
}
