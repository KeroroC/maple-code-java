package com.maplecode.prompt;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalTime;

public record DynamicContext(
    Path cwd,
    boolean isGitRepo,
    String platform,
    String javaVersion,
    String mavenVersion,
    LocalDate date,
    DayOfWeek dayOfWeek,
    LocalTime time
) {
    public static DynamicContext capture(Path cwd) {
        boolean git = Files.exists(cwd.resolve(".git"));
        String os = System.getProperty("os.name")
            + " (" + System.getProperty("os.arch") + ")";
        String java = System.getProperty("java.version");
        String maven = detectMavenVersion();
        LocalDate date = LocalDate.now();
        return new DynamicContext(cwd, git, os, java, maven,
            date, date.getDayOfWeek(), LocalTime.now().withNano(0));
    }

    static String detectMavenVersion() {
        try {
            Process p = new ProcessBuilder("mvn", "-v")
                .redirectErrorStream(true)
                .start();
            boolean done = p.waitFor(2, java.util.concurrent.TimeUnit.SECONDS);
            if (!done) { p.destroyForcibly(); return "unknown"; }
            if (p.exitValue() != 0) return "unknown";
            String out = new String(p.getInputStream().readAllBytes(),
                java.nio.charset.StandardCharsets.UTF_8);
            for (String line : out.split("\n")) {
                if (line.startsWith("Apache Maven")) {
                    String[] parts = line.split("\\s+");
                    if (parts.length >= 3) return parts[2];
                }
            }
            return "unknown";
        } catch (Exception e) {
            return "unknown";
        }
    }
}
