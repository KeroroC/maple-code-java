package com.maplecode.prompt;

import java.nio.file.Path;
import java.time.LocalDate;
import java.time.LocalTime;

public record DynamicContext(
    Path cwd,
    boolean isGitRepo,
    String platform,
    String javaVersion,
    String mavenVersion,
    LocalDate date,
    LocalTime time
) {}
