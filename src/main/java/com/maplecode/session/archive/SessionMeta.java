package com.maplecode.session.archive;

import java.time.Instant;

public record SessionMeta(
    String id,
    int messageCount,
    Instant lastActivity
) {}
