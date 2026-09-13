package com.icecode.workbench.knowledge;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.icecode.workbench.auth.AppConfigRepository;
import com.icecode.workbench.settings.SettingsService;

/**
 * Obsidian Vault 的文件读写。只追加写入 00-Inbox/ 下的新笔记（重名自动加序号），
 * 从不修改或删除用户已有文件；RAG 索引只读扫描整个 Vault。
 *
 * <p>索引扫描的性能约束（2026-09-13 实测）：工作台跑在 Docker 容器里，Vault 是宿主机盘符的
 * 绑定挂载，每次目录读取/stat 都要走一趟 9p，比宿主机原生慢约 60 倍。用户 Vault 里
 * 有 1035 个目录 / 8754 个条目，其中绝大多数是 <b>无关内容</b>（一个 5665 条的 Python 虚拟环境
 * .jupymd、.git、.obsidian、.trash…），真正要索引的 .md 只有 344 个。所以这里做三件事：
 * <ol>
 *   <li><b>剪枝而非事后过滤</b>：遍历时遇到隐藏目录直接 {@code SKIP_SUBTREE}，
 *       不再沿用 {@code Files.walk().filter()}（那会先枚举整棵树，实测 10.2 s → 0.6 s）；</li>
 *   <li><b>快照缓存</b>：一份 {@link VaultIndex} 在 TTL 内直接复用，请求路径零磁盘访问；</li>
 *   <li><b>落盘复用</b>：快照同时写进 data-dir，进程重启后不必从头再扫一遍。</li>
 * </ol>
 */
@Service
public class ObsidianVaultService {

    private static final Logger LOGGER = LoggerFactory.getLogger(ObsidianVaultService.class);
    private static final String INBOX_DIR = "00-Inbox";
    private static final int MAX_FILE_BYTES = 512 * 1024;
    // 分片上限只是「最多取多少」，不预分配内存（实际占用随真实分片数增长）。
    // 原值 2000 会在中等规模 Vault（约 340 篇）上触发截断，导致大量笔记无法被检索到。
    private static final int MAX_CHUNKS = 20000;

    /**
     * 索引快照的有效期：窗口内任何请求都不碰磁盘，直接返回内存里的快照。
     * 之所以必须留这个窗口，是因为「热路径也要遍历」正是本功能最初的病灶 ——
     * 旧实现每次调用都要算一遍指纹（容器内 10.2 s），用户看到的就是「每次强刷都要等很久」。
     * 新鲜度由两条兜底保证：应用自己写 Vault 时主动 {@link #invalidateIndex()}，
     * 外部（Obsidian 里改笔记）最多晚 TTL 生效一次。
     */
    private static final long INDEX_TTL_MS = 3 * 60 * 1000L;

    private static final String INDEX_FILE_NAME = "vault-index.json";
    private static final int INDEX_FORMAT_VERSION = 1;

    private final AppConfigRepository configRepository;
    private final Path dataDir;
    private final ObjectMapper objectMapper = new ObjectMapper();

    private volatile VaultIndex cachedIndex;
    private final Object indexLock = new Object();

    public ObsidianVaultService(AppConfigRepository configRepository,
                                @Value("${workbench.data-dir}") String dataDir) {
        this.configRepository = configRepository;
        this.dataDir = Paths.get(dataDir).toAbsolutePath().normalize();
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
            // 刚写进去的笔记必须立刻可检索：不等 TTL 到期，主动作废快照。
            // 这是「知识库问答里搜不到刚收藏的笔记」的唯一防线。
            invalidateIndex();
            return INBOX_DIR + "/" + target.getFileName().toString();
        } catch (Exception exception) {
            LOGGER.warn("写入 Vault 笔记失败：{}", exception.getMessage());
            return null;
        }
    }

    /**
     * 当前 Vault 的索引快照。调用方拿到的对象是只读的，可以安全地在多线程间共享。
     *
     * <p>分层：内存快照（TTL 内零 IO）→ 剪枝指纹校验（约 0.6 s，未变则只续期）→ 重扫（约 1.3 s）。
     */
    public VaultIndex vaultIndex() {
        Path vault = vaultPath();
        if (vault == null || !Files.isDirectory(vault)) return VaultIndex.empty();

        String vaultKey = vault.toString();
        long now = System.currentTimeMillis();
        VaultIndex current = cachedIndex;
        if (isFresh(current, vaultKey, now)) return current;

        synchronized (indexLock) {
            current = cachedIndex;
            if (isFresh(current, vaultKey, now)) return current;

            // 内存里没有（进程刚起来）就看落盘的那份，省掉一次整树遍历 + 全量读文件。
            if (current == null || !vaultKey.equals(current.vaultPath)) {
                VaultIndex fromDisk = loadIndexFile(vaultKey);
                if (fromDisk != null) {
                    current = fromDisk;
                    cachedIndex = current;
                    if (isFresh(current, vaultKey, now)) return current;
                }
            }

            String fingerprint = fingerprint(vault);
            if (current != null && vaultKey.equals(current.vaultPath) && fingerprint.equals(current.fingerprint)) {
                // 内容没变，只是窗口过期：续期即可，不做最贵的那一步。
                current = current.touched(now);
                cachedIndex = current;
                return current;
            }

            VaultIndex scanned = scan(vault, vaultKey, fingerprint, now);
            cachedIndex = scanned;
            saveIndexFile(scanned);
            return scanned;
        }
    }

    /** 作废当前快照（内存 + 落盘），下一次 {@link #vaultIndex()} 会重新扫描。 */
    public void invalidateIndex() {
        synchronized (indexLock) {
            cachedIndex = null;
            try {
                Files.deleteIfExists(indexFilePath());
            } catch (IOException exception) {
                LOGGER.warn("删除索引快照失败：{}", exception.getMessage());
            }
        }
    }

    private boolean isFresh(VaultIndex index, String vaultKey, long now) {
        return index != null && vaultKey.equals(index.vaultPath) && now - index.builtAt < INDEX_TTL_MS;
    }

    // ---------------------------------------------------------------- 扫描

    /**
     * 剪枝指纹：文件数 + 最新修改时间 + 总字节数 + 相对路径哈希和。
     * 只用 {@code BasicFileAttributes}（遍历时已顺带拿到），不额外 stat、不读文件内容。
     */
    private String fingerprint(Path vault) {
        final long[] count = {0L};
        final long[] maxModified = {0L};
        final long[] totalSize = {0L};
        final long[] pathHash = {0L};
        try {
            Files.walkFileTree(vault, new SimpleFileVisitor<Path>() {
                @Override public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
                    return isHiddenDirectory(vault, dir) ? FileVisitResult.SKIP_SUBTREE : FileVisitResult.CONTINUE;
                }
                @Override public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                    if (!isIndexableMarkdown(file, attrs)) return FileVisitResult.CONTINUE;
                    count[0]++;
                    totalSize[0] += attrs.size();
                    long modified = attrs.lastModifiedTime().toMillis();
                    if (modified > maxModified[0]) maxModified[0] = modified;
                    pathHash[0] += vault.relativize(file).toString().hashCode();
                    return FileVisitResult.CONTINUE;
                }
                @Override public FileVisitResult visitFileFailed(Path file, IOException exception) {
                    return FileVisitResult.CONTINUE;
                }
                @Override public FileVisitResult postVisitDirectory(Path dir, IOException exception) {
                    return FileVisitResult.CONTINUE;
                }
            });
        } catch (IOException exception) {
            LOGGER.warn("计算 Vault 指纹失败：{}", exception.getMessage());
            return "error:" + System.nanoTime();
        }
        return count[0] + ":" + maxModified[0] + ":" + totalSize[0] + ":" + pathHash[0];
    }

    /** 全量扫描：读全部 .md 并按 ATX 标题分片。 */
    private VaultIndex scan(Path vault, String vaultKey, String fingerprint, long now) {
        final List<VaultChunk> chunks = new ArrayList<VaultChunk>();
        try {
            Files.walkFileTree(vault, new SimpleFileVisitor<Path>() {
                @Override public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
                    return isHiddenDirectory(vault, dir) ? FileVisitResult.SKIP_SUBTREE : FileVisitResult.CONTINUE;
                }
                @Override public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                    if (!isIndexableMarkdown(file, attrs)) return FileVisitResult.CONTINUE;
                    parseFile(vault, file, chunks);
                    return FileVisitResult.CONTINUE;
                }
                @Override public FileVisitResult visitFileFailed(Path file, IOException exception) {
                    return FileVisitResult.CONTINUE;
                }
                @Override public FileVisitResult postVisitDirectory(Path dir, IOException exception) {
                    return FileVisitResult.CONTINUE;
                }
            });
        } catch (IOException exception) {
            LOGGER.warn("扫描 Vault 失败：{}", exception.getMessage());
        }
        Set<String> files = new HashSet<String>();
        for (VaultChunk chunk : chunks) files.add(chunk.file);
        return new VaultIndex(vaultKey, fingerprint, now, chunks, files.size());
    }

    /**
     * 隐藏目录整棵剪掉 —— 这是本次优化的核心。
     *
     * <p>不能写成 {@code Files.walk(vault).filter(p -> !isHidden(p))}：{@code Files.walk} 是
     * 先枚举整棵树再交给 filter，被过滤掉的目录照样要付 readdir + stat 的代价。实测用户 Vault 里
     * {@code 知识体系/.jupymd}（Python 虚拟环境）一个目录就有 5665 个条目，加上 .git 的 2129 个，
     * 占全部 8754 个条目的 90% 以上，而它们永远不可能产出可检索的笔记。
     *
     * <p>剪枝语义与旧的 {@code isHidden()} 完全一致：路径中任何一段以 {@code .} 开头都算隐藏。
     * 目录走 {@code SKIP_SUBTREE}，文件在 {@link #isIndexableMarkdown} 里单独挡。
     */
    private boolean isHiddenDirectory(Path vault, Path dir) {
        return !dir.equals(vault) && dir.getFileName().toString().startsWith(".");
    }

    private boolean isIndexableMarkdown(Path file, BasicFileAttributes attrs) {
        String name = file.getFileName().toString();
        if (name.startsWith(".")) return false;
        if (!name.toLowerCase().endsWith(".md")) return false;
        return attrs.isRegularFile() && attrs.size() <= MAX_FILE_BYTES;
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

    private String sanitizeFileName(String title) {
        String name = title == null ? "" : title.replaceAll("[\\\\/:*?\"<>|\\p{Cntrl}]", "").trim();
        if (name.length() > 60) name = name.substring(0, 60).trim();
        return name.isEmpty() ? "未命名笔记" : name;
    }

    // ------------------------------------------------------------ 落盘

    private Path indexFilePath() { return dataDir.resolve(INDEX_FILE_NAME); }

    private VaultIndex loadIndexFile(String vaultKey) {
        Path file = indexFilePath();
        if (!Files.isRegularFile(file)) return null;
        try {
            StoredIndex stored = objectMapper.readValue(Files.readAllBytes(file), StoredIndex.class);
            if (stored == null || stored.version != INDEX_FORMAT_VERSION) return null;
            // Vault 换过（用户改了设置）就丢弃，否则会把上一个库的笔记当成这个库的。
            if (stored.vaultPath == null || !stored.vaultPath.equals(vaultKey)) return null;
            List<VaultChunk> chunks = new ArrayList<VaultChunk>();
            if (stored.chunks != null) {
                for (StoredChunk item : stored.chunks) {
                    if (item == null || item.c == null) continue;
                    chunks.add(new VaultChunk(item.f, item.h, item.c));
                }
            }
            return new VaultIndex(vaultKey, stored.fingerprint, stored.builtAt, chunks, stored.fileCount);
        } catch (Exception exception) {
            // 快照只是缓存，坏了就当没有 —— 绝不因为它让知识库整体不可用。
            LOGGER.warn("读取索引快照失败，忽略并删除：{}", exception.getMessage());
            try { Files.deleteIfExists(file); } catch (IOException ignored) { }
            return null;
        }
    }

    private void saveIndexFile(VaultIndex index) {
        try {
            StoredIndex stored = new StoredIndex();
            stored.version = INDEX_FORMAT_VERSION;
            stored.vaultPath = index.vaultPath;
            stored.fingerprint = index.fingerprint;
            stored.builtAt = index.builtAt;
            stored.fileCount = index.fileCount;
            stored.chunks = new ArrayList<StoredChunk>(index.chunks.size());
            for (VaultChunk chunk : index.chunks) stored.chunks.add(new StoredChunk(chunk.file, chunk.heading, chunk.content));

            Files.createDirectories(dataDir);
            Path target = indexFilePath();
            Path temp = dataDir.resolve(INDEX_FILE_NAME + ".tmp");
            Files.write(temp, objectMapper.writeValueAsBytes(stored));
            try {
                Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (Exception notAtomic) {
                Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (Exception exception) {
            LOGGER.warn("写入索引快照失败（不影响本次检索）：{}", exception.getMessage());
        }
    }

    // ------------------------------------------------------------ 数据结构

    /** Vault 索引快照：构造完成后不再变化，可安全跨线程共享。 */
    public static final class VaultIndex {
        public final List<VaultChunk> chunks;
        public final int fileCount;
        public final int chunkCount;
        final String vaultPath;
        final String fingerprint;
        final long builtAt;

        VaultIndex(String vaultPath, String fingerprint, long builtAt, List<VaultChunk> chunks, int fileCount) {
            this.vaultPath = vaultPath;
            this.fingerprint = fingerprint == null ? "" : fingerprint;
            this.builtAt = builtAt;
            this.chunks = chunks;
            this.fileCount = fileCount;
            this.chunkCount = chunks.size();
        }

        static VaultIndex empty() {
            return new VaultIndex("", "", 0L, new ArrayList<VaultChunk>(), 0);
        }

        VaultIndex touched(long now) {
            return new VaultIndex(vaultPath, fingerprint, now, chunks, fileCount);
        }
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

    /** 落盘用的 DTO。字段必须 public 且带无参构造，Jackson 才能直接读写。 */
    public static class StoredIndex {
        public int version;
        public String vaultPath;
        public String fingerprint;
        public long builtAt;
        public int fileCount;
        public List<StoredChunk> chunks;
    }

    public static class StoredChunk {
        public String f;
        public String h;
        public String c;

        public StoredChunk() { }

        StoredChunk(String f, String h, String c) {
            this.f = f;
            this.h = h;
            this.c = c;
        }
    }
}
