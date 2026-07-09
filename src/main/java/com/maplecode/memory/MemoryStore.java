package com.maplecode.memory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

public class MemoryStore {

    private static final String INDEX_FILENAME = "MEMORY.md";
    private static final DateTimeFormatter TS_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");

    private final Path userBase;
    private final Path projectBase;

    public MemoryStore() {
        this(MemoryScope.USER.basePath(), MemoryScope.PROJECT.basePath());
    }

    public MemoryStore(Path userBase, Path projectBase) {
        this.userBase = userBase;
        this.projectBase = projectBase;
    }

    public List<MemoryEntry> loadIndex(MemoryScope scope) {
        Path indexFile = basePath(scope).resolve(INDEX_FILENAME);
        if (!Files.exists(indexFile)) {
            return List.of();
        }
        try {
            String text = Files.readString(indexFile, StandardCharsets.UTF_8);
            return parseIndex(text, scope);
        } catch (IOException e) {
            return List.of();
        }
    }

    public String loadIndexText(MemoryScope scope) {
        Path indexFile = basePath(scope).resolve(INDEX_FILENAME);
        if (!Files.exists(indexFile)) {
            return "";
        }
        try {
            return Files.readString(indexFile, StandardCharsets.UTF_8);
        } catch (IOException e) {
            return "";
        }
    }

    public String readContent(MemoryScope scope, MemoryCategory category, String name) {
        return findFileByName(scope, category, name)
                .map(this::parseFrontmatter)
                .map(fp -> fp.content)
                .orElse(null);
    }

    public void executeOp(MemoryOp op) {
        switch (op) {
            case MemoryOp.Create c -> doCreate(c);
            case MemoryOp.Update u -> doUpdate(u);
            case MemoryOp.Delete d -> doDelete(d);
        }
    }

    public void rebuildIndex(MemoryScope scope) {
        Path base = basePath(scope);
        if (!Files.exists(base)) {
            return;
        }
        try {
            Files.createDirectories(base);
        } catch (IOException e) {
            return;
        }
        List<MemoryEntry> allEntries = new ArrayList<>();
        for (MemoryCategory cat : MemoryCategory.values()) {
            if (cat.scope() != scope) continue;
            Path catDir = base.resolve(cat.dirName());
            if (!Files.isDirectory(catDir)) continue;
            for (Path mdFile : listMdFiles(catDir)) {
                FrontmatterParsed fp = parseFrontmatter(mdFile);
                if (fp != null) {
                    Path relativePath = base.relativize(mdFile);
                    allEntries.add(new MemoryEntry(
                            fp.name, cat, fp.summary, relativePath.toString(), fp.updated));
                }
            }
        }
        String indexContent = buildIndex(allEntries);
        try {
            Files.writeString(base.resolve(INDEX_FILENAME), indexContent, StandardCharsets.UTF_8);
        } catch (IOException e) {
            // silently ignore
        }
    }

    public void clearAll(MemoryScope scope) {
        Path base = basePath(scope);
        if (!Files.exists(base)) {
            return;
        }
        try {
            // Walk in reverse so files are deleted before parent directories
            Files.walk(base).sorted(java.util.Comparator.reverseOrder()).forEach(path -> {
                try {
                    if (Files.isRegularFile(path) && path.toString().endsWith(".md")) {
                        Files.delete(path);
                    } else if (Files.isDirectory(path) && !path.equals(base)) {
                        // Only delete empty dirs; ignore non-empty ones
                        try {
                            Files.delete(path);
                        } catch (java.nio.file.DirectoryNotEmptyException ignored) {
                        }
                    }
                } catch (IOException ignored) {
                }
            });
        } catch (IOException ignored) {
        }
    }

    // --- CRUD helpers ---

    private void doCreate(MemoryOp.Create c) {
        Path catDir = basePath(c.category().scope()).resolve(c.category().dirName());
        try {
            Files.createDirectories(catDir);
        } catch (IOException e) {
            return;
        }
        String slug = toSlug(c.name());
        int seq = nextSequence(catDir);
        String filename = String.format("%03d-%s.md", seq, slug);
        Path filePath = catDir.resolve(filename);
        String now = LocalDateTime.now().format(TS_FMT);
        String frontmatter = buildFrontmatter(c.name(), c.category().dirName(), now, now);
        try {
            Files.writeString(filePath, frontmatter + c.content(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            return;
        }
        rebuildIndex(c.category().scope());
    }

    private void doUpdate(MemoryOp.Update u) {
        for (MemoryScope scope : MemoryScope.values()) {
            for (MemoryCategory cat : MemoryCategory.values()) {
                if (cat.scope() != scope) continue;
                Path catDir = basePath(scope).resolve(cat.dirName());
                if (!Files.isDirectory(catDir)) continue;
                for (Path mdFile : listMdFiles(catDir)) {
                    FrontmatterParsed fp = parseFrontmatter(mdFile);
                    if (fp != null && fp.name.equals(u.name())) {
                        String now = LocalDateTime.now().format(TS_FMT);
                        String frontmatter = buildFrontmatter(fp.name, cat.dirName(), fp.created, now);
                        try {
                            Files.writeString(mdFile, frontmatter + u.content(), StandardCharsets.UTF_8);
                        } catch (IOException e) {
                            return;
                        }
                        rebuildIndex(scope);
                        return;
                    }
                }
            }
        }
    }

    private void doDelete(MemoryOp.Delete d) {
        for (MemoryScope scope : MemoryScope.values()) {
            for (MemoryCategory cat : MemoryCategory.values()) {
                if (cat.scope() != scope) continue;
                Path catDir = basePath(scope).resolve(cat.dirName());
                if (!Files.isDirectory(catDir)) continue;
                for (Path mdFile : listMdFiles(catDir)) {
                    FrontmatterParsed fp = parseFrontmatter(mdFile);
                    if (fp != null && fp.name.equals(d.name())) {
                        try {
                            Files.delete(mdFile);
                        } catch (IOException e) {
                            return;
                        }
                        rebuildIndex(scope);
                        return;
                    }
                }
            }
        }
    }

    // --- Index parsing ---

    static List<MemoryEntry> parseIndex(String text, MemoryScope scope) {
        List<MemoryEntry> entries = new ArrayList<>();
        MemoryCategory currentCategory = null;
        for (String line : text.split("\n")) {
            if (line.startsWith("## ")) {
                String catDirName = line.substring(3).trim();
                currentCategory = findCategoryByDirName(catDirName, scope);
            } else if (line.startsWith("- [") && currentCategory != null) {
                int closeBracket = line.indexOf(']');
                int closeParen = line.indexOf(')');
                if (closeBracket > 0 && closeParen > closeBracket) {
                    String name = line.substring(3, closeBracket);
                    String path = line.substring(closeBracket + 2, closeParen);
                    String summary = "";
                    int dash = line.indexOf(" — ", closeParen);
                    if (dash >= 0) {
                        summary = line.substring(dash + 2).trim();
                    }
                    entries.add(new MemoryEntry(name, currentCategory, summary, path, ""));
                }
            }
        }
        return entries;
    }

    private static MemoryCategory findCategoryByDirName(String dirName, MemoryScope scope) {
        for (MemoryCategory cat : MemoryCategory.values()) {
            if (cat.scope() == scope && cat.dirName().equals(dirName)) {
                return cat;
            }
        }
        return null;
    }

    // --- File path helpers ---

    private Path basePath(MemoryScope scope) {
        return switch (scope) {
            case USER -> userBase;
            case PROJECT -> projectBase;
        };
    }

    private Optional<Path> findFileByName(MemoryScope scope, MemoryCategory category, String name) {
        Path catDir = basePath(scope).resolve(category.dirName());
        if (!Files.isDirectory(catDir)) {
            return Optional.empty();
        }
        for (Path mdFile : listMdFiles(catDir)) {
            FrontmatterParsed fp = parseFrontmatter(mdFile);
            if (fp != null && fp.name.equals(name)) {
                return Optional.of(mdFile);
            }
        }
        return Optional.empty();
    }

    private List<Path> listMdFiles(Path dir) {
        List<Path> result = new ArrayList<>();
        try (var stream = Files.list(dir)) {
            stream.filter(p -> p.toString().endsWith(".md"))
                  .sorted()
                  .forEach(result::add);
        } catch (IOException e) {
            // silently ignore
        }
        return result;
    }

    private int nextSequence(Path catDir) {
        int maxSeq = 0;
        for (Path mdFile : listMdFiles(catDir)) {
            String filename = mdFile.getFileName().toString();
            int dash = filename.indexOf('-');
            if (dash > 0) {
                try {
                    int seq = Integer.parseInt(filename.substring(0, dash));
                    if (seq > maxSeq) maxSeq = seq;
                } catch (NumberFormatException e) {
                    // skip non-numeric prefix
                }
            }
        }
        return maxSeq + 1;
    }

    private static String toSlug(String name) {
        String slug = name.toLowerCase()
                          .replaceAll("[^a-z0-9\\-]", "-")
                          .replaceAll("-{2,}", "-")
                          .replaceAll("^-|-$", "");
        return slug.substring(0, Math.min(20, slug.length()));
    }

    // --- Frontmatter ---

    private static final String FM_START = "---\n";
    private static final String FM_END = "---\n";

    private record FrontmatterParsed(String name, String category, String created, String updated, String content, String summary) {}

    private String buildFrontmatter(String name, String category, String created, String updated) {
        return FM_START
                + "name: " + name + "\n"
                + "category: " + category + "\n"
                + "created: " + created + "\n"
                + "updated: " + updated + "\n"
                + FM_END + "\n";
    }

    private FrontmatterParsed parseFrontmatter(Path file) {
        try {
            String text = Files.readString(file, StandardCharsets.UTF_8);
            return parseFrontmatterText(text);
        } catch (IOException e) {
            return null;
        }
    }

    static FrontmatterParsed parseFrontmatterText(String text) {
        if (!text.startsWith(FM_START)) {
            return null;
        }
        int endIdx = text.indexOf("---\n", FM_START.length());
        if (endIdx < 0) {
            return null;
        }
        String fmBlock = text.substring(FM_START.length(), endIdx);
        String content = text.substring(endIdx + FM_END.length());
        if (content.startsWith("\n")) {
            content = content.substring(1);
        }
        String name = null, category = null, created = null, updated = null;
        for (String line : fmBlock.split("\n")) {
            int colon = line.indexOf(':');
            if (colon < 0) continue;
            String key = line.substring(0, colon).trim();
            String value = line.substring(colon + 1).trim();
            switch (key) {
                case "name" -> name = value;
                case "category" -> category = value;
                case "created" -> created = value;
                case "updated" -> updated = value;
            }
        }
        if (name == null || category == null) {
            return null;
        }
        String summary = content.length() <= 50 ? content : content.substring(0, 50);
        return new FrontmatterParsed(name, category, created, updated, content, summary);
    }

    // --- Index building ---

    private static String buildIndex(List<MemoryEntry> entries) {
        StringBuilder sb = new StringBuilder();
        sb.append("# Memory Index\n\n");
        MemoryCategory lastCat = null;
        for (MemoryEntry entry : entries) {
            if (lastCat != entry.category()) {
                sb.append("## ").append(entry.category().dirName()).append("\n");
                lastCat = entry.category();
            }
            sb.append("- [").append(entry.name()).append("](")
              .append(entry.relativePath()).append(") — ")
              .append(entry.summary()).append("\n");
        }
        return sb.toString();
    }
}
