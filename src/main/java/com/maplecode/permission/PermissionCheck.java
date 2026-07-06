package com.maplecode.permission;

import java.util.Optional;

public interface PermissionCheck {
    Optional<Decision> check(PermissionRequest req, PermissionContext ctx);
}
