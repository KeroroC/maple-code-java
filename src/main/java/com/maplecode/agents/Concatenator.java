package com.maplecode.agents;

import java.nio.charset.StandardCharsets;
import java.util.List;

public final class Concatenator {

    private static final String SEPARATOR = "\n\n---\n\n";
    private static final String TRUNCATION_MARKER = "\n\n[truncated: AGENTS.md total > 64KB]";

    private Concatenator() {}

    public static String join(List<String> layers) {
        List<String> nonEmpty = layers.stream()
            .filter(s -> s != null && !s.isBlank())
            .toList();
        String joined = String.join(SEPARATOR, nonEmpty);
        byte[] bytes = joined.getBytes(StandardCharsets.UTF_8);
        int maxBytes = IncludeLimits.defaults().maxTotalBytes();
        if (bytes.length > maxBytes) {
            System.err.println("[agents-md] total size " + bytes.length
                + " bytes exceeds max " + maxBytes + "; truncating");
            // 按字节截断（避免 UTF-8 多字节字符中间切断）
            String truncated = new String(bytes, 0, maxBytes, StandardCharsets.UTF_8);
            return truncated + TRUNCATION_MARKER;
        }
        return joined;
    }
}
