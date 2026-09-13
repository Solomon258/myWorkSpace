package com.icecode.workbench.knowledge;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import com.icecode.workbench.auth.AuthConstants;
import com.icecode.workbench.util.TimeUtil;

@SpringBootTest
@AutoConfigureMockMvc
class KnowledgeFlowTest {

    private static final String TEST_ROOT = "target/test-workbench/knowledge-" + UUID.randomUUID().toString();

    @TempDir
    Path vaultDir;

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("workbench.data-dir", () -> TEST_ROOT + "/data");
        registry.add("workbench.home-dir", () -> TEST_ROOT);
    }

    @Autowired private MockMvc mockMvc;
    @Autowired private JdbcTemplate jdbcTemplate;
    private MockHttpSession session;

    @BeforeEach
    void setUp() {
        jdbcTemplate.update("DELETE FROM knowledge_note");
        jdbcTemplate.update("DELETE FROM inbox_item");
        jdbcTemplate.update("DELETE FROM activity_log");
        jdbcTemplate.update("UPDATE app_config SET config_value='true' WHERE config_key='app.initialized'");
        jdbcTemplate.update("UPDATE app_config SET config_value='Asia/Shanghai' WHERE config_key='app.timezone'");
        jdbcTemplate.update("UPDATE app_config SET config_value='false' WHERE config_key='ai.enabled'");
        jdbcTemplate.update("UPDATE app_config SET config_value=? WHERE config_key='obsidian.vault_path'",
                vaultDir.toAbsolutePath().toString());
        session = new MockHttpSession();
        session.setAttribute(AuthConstants.SESSION_USER, "tester");
    }

    @Test
    void localRulesSuggestKnowledgeCategory() throws Exception {
        mockMvc.perform(post("/api/v1/inbox").session(session)
                        .contentType("application/json")
                        .content("{\"raw\":\"一篇讲双链笔记如何组织技术方案的文章，值得参考 https://example.com/links\"}"))
                .andExpect(status().isOk());
        mockMvc.perform(post("/api/v1/inbox/classify").session(session)).andExpect(status().isOk());
        String category = jdbcTemplate.queryForObject(
                "SELECT ai_category FROM inbox_item ORDER BY id DESC LIMIT 1", String.class);
        assertThat(category).isEqualTo("knowledge");
    }

    @Test
    void confirmKnowledgeWritesVaultFileAndActivity() throws Exception {
        long inboxId = createInbox("技术方案模板标准结构：背景目标、方案对比、详细设计、容量评估");
        mockMvc.perform(post("/api/v1/inbox/{id}/confirm", inboxId).session(session)
                        .contentType("application/json")
                        .content("{\"category\":\"knowledge\",\"title\":\"技术方案模板结构\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.category").value("knowledge"));

        assertThat(jdbcTemplate.queryForObject(
                "SELECT sync_status FROM knowledge_note WHERE source_inbox_id=?", String.class, inboxId))
                .isEqualTo("synced");
        Path inboxDir = vaultDir.resolve("00-Inbox");
        assertThat(Files.isDirectory(inboxDir)).isTrue();
        try (java.util.stream.Stream<Path> files = Files.list(inboxDir)) {
            Path note = files.filter(p -> p.getFileName().toString().contains("技术方案模板结构"))
                    .findFirst().orElseThrow(() -> new AssertionError("Vault 中未找到笔记文件"));
            String markdown = new String(Files.readAllBytes(note), StandardCharsets.UTF_8);
            assertThat(markdown).contains("source: personal-workbench").contains("# 技术方案模板结构");
        }
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM activity_log WHERE log_type='knowledge'", Integer.class)).isEqualTo(1);
    }

    @Test
    void confirmKnowledgeRequiresVault() throws Exception {
        jdbcTemplate.update("UPDATE app_config SET config_value='' WHERE config_key='obsidian.vault_path'");
        long inboxId = createInbox("值得沉淀的方法论笔记");
        mockMvc.perform(post("/api/v1/inbox/{id}/confirm", inboxId).session(session)
                        .contentType("application/json")
                        .content("{\"category\":\"knowledge\",\"title\":\"方法论笔记\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(3007));
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM knowledge_note", Integer.class)).isEqualTo(0);
    }

    @Test
    void askReturnsExtractiveAnswerWithCitationsWithoutLlm() throws Exception {
        writeVaultNote("复核平台限流策略 v3.md",
                "---\ntags:\n  - 架构\n---\n# 复核平台限流策略 v3\n\n"
                        + "## 多通道限流设计\n\n限流采用 QPS 加日配额双维度：QPS 用令牌桶按通道隔离，日配额按下游平台独立计数，每日零点重置。\n\n"
                        + "## 降级策略\n\n单通道超限时仅熔断该通道，核心通道永不降级，非核心通道按优先级排队。\n");

        mockMvc.perform(post("/api/v1/knowledge/ask").session(session)
                        .contentType("application/json")
                        .content("{\"question\":\"日配额是怎么设计的？\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.answered").value(true))
                .andExpect(jsonPath("$.data.llmUsed").value(false))
                .andExpect(jsonPath("$.data.citations[0].file").value("复核平台限流策略 v3.md"))
                .andExpect(jsonPath("$.data.citations[0].heading").value("多通道限流设计"));
    }

    @Test
    void shortQueryMatchesContentInsteadOfFallingBackToInbox() throws Exception {
        // 回归：相关度阈值曾用固定 MIN_SCORE=6，而每个检索词最多只贡献 7 分
        // （content 1 + heading 4 + file 2）。2 字问题只产出 1 个 CJK 二元组，
        // 实际得分仅 1~3 分，永远够不到 6 → 库里明明有笔记却一律判为「不相关」。
        // 实测用户 Vault 中「限流」命中 21 个文件却搜不到，即此 bug。
        writeVaultNote("限流方案.md", "# 限流\n\n## 令牌桶\n\n限流采用令牌桶按通道隔离，日配额按平台独立计数。\n");

        mockMvc.perform(post("/api/v1/knowledge/ask").session(session)
                        .contentType("application/json")
                        .content("{\"question\":\"限流\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.answered").value(true))
                .andExpect(jsonPath("$.data.capturedToInbox").value(false))
                .andExpect(jsonPath("$.data.citations[0].file").value("限流方案.md"));
    }

    @Test
    void askWithoutMatchCapturesQuestionToInbox() throws Exception {
        writeVaultNote("GTD 实践笔记.md", "# GTD 实践笔记\n\n## 收集箱纪律\n\n收集箱不是仓库，每天固定时段清空一次，清空率比记录数量更重要。\n");

        mockMvc.perform(post("/api/v1/knowledge/ask").session(session)
                        .contentType("application/json")
                        .content("{\"question\":\"量子计算机的最新进展是什么\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.answered").value(false))
                .andExpect(jsonPath("$.data.capturedToInbox").value(true));
        String raw = jdbcTemplate.queryForObject(
                "SELECT raw_content FROM inbox_item WHERE raw_content LIKE '[待补知识]%' ORDER BY id DESC LIMIT 1", String.class);
        assertThat(raw).isEqualTo("[待补知识] 量子计算机的最新进展是什么");
    }

    @Test
    void askRequiresVaultAndIndexStatusReports() throws Exception {
        jdbcTemplate.update("UPDATE app_config SET config_value='' WHERE config_key='obsidian.vault_path'");
        mockMvc.perform(post("/api/v1/knowledge/ask").session(session)
                        .contentType("application/json")
                        .content("{\"question\":\"任何内容\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(3007));

        jdbcTemplate.update("UPDATE app_config SET config_value=? WHERE config_key='obsidian.vault_path'",
                vaultDir.toAbsolutePath().toString());
        writeVaultNote("团队 1on1 方法论.md", "# 1on1\n\n## 基本框架\n\n每两周一次每次三十分钟，结构为对方议题、你的反馈、成长话题各十分钟。\n");
        mockMvc.perform(get("/api/v1/knowledge/index").session(session))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.configured").value(true))
                .andExpect(jsonPath("$.data.fileCount").value(1))
                .andExpect(jsonPath("$.data.chunkCount").value(1));
    }

    @Test
    void retrySyncRecreatesVaultFile() throws Exception {
        long inboxId = createInbox("缓存淘汰策略学习笔记总结");
        mockMvc.perform(post("/api/v1/inbox/{id}/confirm", inboxId).session(session)
                        .contentType("application/json")
                        .content("{\"category\":\"knowledge\",\"title\":\"缓存淘汰策略\"}"))
                .andExpect(status().isOk());
        long noteId = jdbcTemplate.queryForObject(
                "SELECT id FROM knowledge_note WHERE source_inbox_id=?", Long.class, inboxId).longValue();

        try (java.util.stream.Stream<Path> files = Files.list(vaultDir.resolve("00-Inbox"))) {
            files.forEach(p -> { try { Files.delete(p); } catch (Exception ignored) { } });
        }
        jdbcTemplate.update("UPDATE knowledge_note SET sync_status='failed' WHERE id=?", noteId);

        mockMvc.perform(post("/api/v1/knowledge/notes/{id}/sync", noteId).session(session))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.syncStatus").value("synced"));
        try (java.util.stream.Stream<Path> files = Files.list(vaultDir.resolve("00-Inbox"))) {
            assertThat(files.count()).isGreaterThanOrEqualTo(1);
        }
    }

    private long createInbox(String raw) {
        jdbcTemplate.update(
                "INSERT INTO inbox_item(raw_content, content_type, source, status, created_at, updated_at, is_demo)"
                        + " VALUES (?,'text','web','processed',?,?,0)",
                raw, TimeUtil.now("Asia/Shanghai"), TimeUtil.now("Asia/Shanghai"));
        return jdbcTemplate.queryForObject("SELECT id FROM inbox_item ORDER BY id DESC LIMIT 1", Long.class).longValue();
    }

    private void writeVaultNote(String fileName, String markdown) throws Exception {
        Files.write(Paths.get(vaultDir.toString(), fileName), markdown.getBytes(StandardCharsets.UTF_8));
    }
}
