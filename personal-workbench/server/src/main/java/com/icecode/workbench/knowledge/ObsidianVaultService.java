package com.icecode.workbench.knowledge;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import com.icecode.workbench.auth.AppConfigRepository;
import com.icecode.workbench.settings.SettingsService;

/**
 * Obsidian Vault 的文件读写。只追加写入 00-Inbox/ 下的新笔记（重名自动加序号），
 * 从不修改或删除用户已有文件；RAG 索引只读扫描整个 Vault。
 */
@Service
public class ObsidianVaultService {

    private static final Logger LOGGER = LoggerFactory.getLogger(ObsidianVaultService.class);
    private static final String INBOX_DIR = "00-Inbox";
    private static final int MAX_FILE_BYTES = 512 * 1024;
    // 分片上限只是「最多取多少」，不预分配内存（实际占用随真实分片数增长）。
    // 原值 2000 会在中等规模 Vault（约 340 篇）上触发截断，导致大量笔记无法被检索到。
    private static final int MAX_CHUNKS = 20000;

    private final AppConfigRepository configRepository;

    public ObsidianVaultService(AppConfigRepository configRepository) {
        this.configRepository = configRepository;
    }

    public boolean isConfigured() {
        Path vault = vaultPath();
        return vault != null && Files.isDirectory(vault);
    }

    public String vaultName() {
        Path vault = vaultPath();
        if (vault == null) return "";
        Path name = vault.getFileName();
        return name == null ? "" : name.toString();
    }

    public Path vaultPath() {
        String configured = configRepository.findValue(SettingsService.KEY_OBSIDIAN_VAULT);
        if (configured == null || configured.trim().isEmpty()) return null;
        try {
            return Paths.get(configured.trim()).toAbsolutePath().normalize();
        } catch (Exception exception) {
            LOGGER.warn("Vault 路径非法：{}", configured);
            return null;
        }
    }

    /** 写入 Vault/00-Inbox/&lt;标题&gt;.md，返回相对路径；Vault 未配置或写入失败返回 null。 */
    public String writeInboxNote(String title, String content, List<String> tags, String now) {
        Path vault = vaultPath();
        if (vault == null || !Files.isDirectory(vault)) return null;
        try {
            Path inboxDir = vault.resolve(INBOX_DIR);
            Files.createDirectories(inboxDir);
            String baseName = sanitizeFileName(title);
            Path target = inboxDir.resolve(baseName + ".md");
            int suffix = 2;
            while (Files.exists(target) && suffix < 100) {
                target = inboxDir.resolve(baseName + "-" + suffix + ".md");
                suffix++;
            }
            StringBuilder markdown = new StringBuilder();
            markdown.append("---\ncreated: ").append(now).append("\nsource: personal-workbench\n");
            if (tags != null && !tags.isEmpty()) {
                markdown.append("tags:\n");
                for (String tag : tags) markdown.append("  - ").append(tag).append("\n");
            }
            markdown.append("---\n\n# ").append(title).append("\n\n")
                    .append(content == null ? "" : content).append("\n");
            Files.write(target, markdown.toString().getBytes(StandardCharsets.UTF_8));
            return INBOX_DIR + "/" + target.getFileName().toString();
        } catch (Exception exception) {
            LOGGER.warn("写入 Vault 笔记失败：{}", exception.getMessage());
            return null;
        }
    }

    /** 只读扫描 Vault 全部 .md，按 ATX 标题分片；剥离 YAML frontmatter 与 Obsidian 专有语法。 */
    public List<VaultChunk> readChunks() {
        List<VaultChunk> chunks = new ArrayList<VaultChunk>();
        Path vault = vaultPath();
        if (vault == null || !Files.isDirectory(vault)) return chunks;
        try (Stream<Path> stream = Files.walk(vault)) {
            stream.filter(Files::isRegularFile)
                    .filter(p -> p.getFileName().toString().toLowerCase().endsWith(".md"))
                    .filter(p -> !isHidden(vault, p))
                    .filter(p -> {
                        try { return Files.size(p) <= MAX_FILE_BYTES; }
                        catch (IOException exception) { return false; }
                    })
                    .forEach(p -> parseFile(vault, p, chunks));
        } catch (IOException exception) {
            LOGGER.warn("扫描 Vault 失败：{}", exception.getMessage());
        }
        return chunks;
    }

    /** 索引指纹：文件数 + 最新修改时间，用于判断缓存是否过期。 */
    public String indexFingerprint() {
        Path vault = vaultPath();
        if (vault == null || !Files.isDirectory(vault)) return "none";
        try (Stream<Path> stream = Files.walk(vault)) {
            long[] stats = stream.filter(Files::isRegularFile)
                    .filter(p -> p.getFileName().toString().toLowerCase().endsWith(".md"))
                    .mapToLong(p -> {
                        try { return Files.getLastModifiedTime(p).toMillis(); }
                        catch (IOException exception) { return 0L; }
                    })
                    .sorted()
                    .toArray();
            return stats.length + ":" + (stats.length == 0 ? 0 : stats[stats.length - 1]);
        } catch (IOException exception) {
            return "error";
        }
    }

    private void parseFile(Path vault, Path file, List<VaultChunk> chunks) {
        if (chunks.size() >= MAX_CHUNKS) return;
        String relative = vault.relativize(file).toString().replace('\\', '/');
        List<String> lines;
        try {
            lines = Files.readAllLines(file, StandardCharsets.UTF_8);
        } catch (Exception exception) {
            return;
        }
        String heading = "（开篇）";
        StringBuilder body = new StringBuilder();
        boolean inFrontmatter = false;
        boolean frontmatterDone = false;
        for (int i = 0; i < lines.size(); i++) {
            String line = lines.get(i);
            if (!frontmatterDone && i == 0 && "---".equals(line.trim())) { inFrontmatter = true; continue; }
            if (inFrontmatter) {
                if ("---".equals(line.trim())) { inFrontmatter = false; frontmatterDone = true; }
                continue;
            }
            frontmatterDone = true;
            String trimmed = line.trim();
            if (trimmed.startsWith("#")) {
                addChunk(chunks, relative, heading, body);
                heading = trimmed.replaceAll("^#+\\s*", "").replaceAll("#+\\s*$", "");
                if (heading.isEmpty()) heading = "（无标题小节）";
                body = new StringBuilder();
            } else {
                body.append(line).append('\n');
            }
        }
        addChunk(chunks, relative, heading, body);
    }

    private void addChunk(List<VaultChunk> chunks, String file, String heading, StringBuilder body) {
        if (chunks.size() >= MAX_CHUNKS) return;
        String text = body == null ? "" : body.toString();
        // 去掉 Obsidian 双链/高亮/标注语法，便于检索与展示
        text = text.replaceAll("\\[\\[([^\\]|]+\\|)?([^\\]]+)\\]\\]", "$2")
                .replace("==", "").replaceAll("%%[^%]*%%", "").trim();
        if (text.length() < 20) return;
        chunks.add(new VaultChunk(file, heading, text.length() > 1200 ? text.substring(0, 1200) : text));
    }

    private boolean isHidden(Path vault, Path file) {
        Path relative = vault.relativize(file);
        for (Path part : relative) {
            if (part.toString().startsWith(".")) return true;
        }
        return false;
    }

    private String sanitizeFileName(String title) {
        String name = title == null ? "" : title.replaceAll("[\\\\/:*?\"<>|\\p{Cntrl}]", "").trim();
        if (name.length() > 60) name = name.substring(0, 60).trim();
        return name.isEmpty() ? "未命名笔记" : name;
    }

    static class VaultChunk {
        final String file;
        final String heading;
        final String content;

        VaultChunk(String file, String heading, String content) {
            this.file = file;
            this.heading = heading;
            this.content = content;
        }
    }
}
