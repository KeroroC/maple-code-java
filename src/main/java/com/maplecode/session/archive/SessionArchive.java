package com.maplecode.session.archive;

import com.maplecode.session.ChatSession;
import com.maplecode.provider.ChatMessage;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;

public final class SessionArchive {

    private final Path sessionsDir;
    private final SessionWriter writer = new SessionWriter();
    private final SessionReader reader = new SessionReader();

    public SessionArchive(Path sessionsDir) {
        this.sessionsDir = sessionsDir;
        try {
            Files.createDirectories(sessionsDir);
        } catch (IOException e) {
            System.err.println("[session] WARN: cannot create sessions dir: " + sessionsDir);
        }
    }

    public String save(ChatSession session) {
        if (session.size() == 0) return null;
        String id = generateId();
        Path target = sessionsDir.resolve(id + ".jsonl");
        try {
            writer.write(session, target);
            return id;
        } catch (Exception e) {
            System.err.println("[session] WARN: save failed: " + e.getMessage());
            return null;
        }
    }

    public List<ChatMessage> load(String idOrPrefix) {
        Path file = resolveFile(idOrPrefix);
        if (file == null) {
            throw new SessionArchiveException("no session found: " + idOrPrefix);
        }
        return reader.read(file);
    }

    public List<SessionMeta> listRecent(int limit) {
        try (var stream = Files.list(sessionsDir)) {
            return stream
                .filter(p -> p.toString().endsWith(".jsonl"))
                .sorted(Comparator.comparing((Path p) -> {
                    try { return Files.getLastModifiedTime(p).toInstant(); }
                    catch (IOException e) { return Instant.EPOCH; }
                }).reversed())
                .limit(limit)
                .map(p -> {
                    String fileName = p.getFileName().toString();
                    String id = fileName.substring(0, fileName.length() - 6);
                    try {
                        long lineCount = Files.lines(p).filter(l -> !l.isBlank()).count();
                        Instant mtime = Files.getLastModifiedTime(p).toInstant();
                        return new SessionMeta(id, (int) lineCount, mtime);
                    } catch (IOException e) {
                        return new SessionMeta(id, 0, Instant.EPOCH);
                    }
                })
                .toList();
        } catch (IOException e) {
            System.err.println("[session] WARN: listRecent failed: " + e.getMessage());
            return List.of();
        }
    }

    public int cleanExpired(Duration maxAge) {
        Instant cutoff = Instant.now().minus(maxAge);
        int count = 0;
        try (var stream = Files.list(sessionsDir)) {
            var files = stream
                .filter(p -> p.toString().endsWith(".jsonl"))
                .toList();
            for (Path f : files) {
                try {
                    Instant mtime = Files.getLastModifiedTime(f).toInstant();
                    if (mtime.isBefore(cutoff)) {
                        Files.delete(f);
                        count++;
                    }
                } catch (IOException ignored) { }
            }
        } catch (IOException e) {
            System.err.println("[session] WARN: cleanExpired failed: " + e.getMessage());
        }
        return count;
    }

    private Path resolveFile(String idOrPrefix) {
        Path exact = sessionsDir.resolve(idOrPrefix + ".jsonl");
        if (Files.exists(exact)) return exact;
        try (var stream = Files.list(sessionsDir)) {
            var matches = stream
                .filter(p -> {
                    String name = p.getFileName().toString();
                    return name.endsWith(".jsonl") && name.startsWith(idOrPrefix);
                })
                .toList();
            if (matches.size() > 1) {
                throw new SessionArchiveException(
                    "ambiguous prefix '" + idOrPrefix + "' matches " + matches.size() + " sessions");
            }
            return matches.isEmpty() ? null : matches.get(0);
        } catch (IOException e) {
            return null;
        }
    }

    private String generateId() {
        String ts = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HHmmss"));
        String uuid = UUID.randomUUID().toString().substring(0, 6);
        return ts + "-" + uuid;
    }
}
