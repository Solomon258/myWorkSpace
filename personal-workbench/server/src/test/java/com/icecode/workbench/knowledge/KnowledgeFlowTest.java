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
    @Autowired private ObsidianVaultService vaultService;
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
    void questionPhrasingDoesNotStealTheMatchFromTheDistinctiveWord() throws Exception {
        // 回归（2026-09-18）：检索词原本就是问句的 CJK 二元组，等权计分。
        // 「复盘应该怎么做」于是产出 复盘/盘应/应该/该怎/怎么/么做，其中「怎么」「该怎」
        // 这些**问句措辞**在笔记小标题里到处都是（「怎么设计」「如何做」），
        // 命中小标题还额外 +4 分，结果压过了真正的关键词「复盘」。
        // 实测在用户真实 Vault（3399 个片段）上，问「复盘应该怎么做」返回的三个片段
        // 全部与复盘无关，模型只能回答「笔记里相关信息有限」——用户看到的就是
        // 「回答只给了几个文档链接、没有内容」。
        // 现在：疑问词先剥离、按语料频次加权、且必须命中一个「稀有词」才算相关。
        writeVaultNote("该怎么做.md",
                "# 该怎么做\n\n## 怎么设计与怎么做\n\n"
                        + "这里讲的是服务端接口的怎么设计与怎么做，属于通用工程问题，和复盘没有关系。\n");
        writeVaultNote("复盘方法.md",
                "# 复盘方法\n\n## 四个步骤\n\n"
                        + "复盘要先还原事实，再区分事实与判断，最后落成一条可执行的改进项。\n");

        mockMvc.perform(post("/api/v1/knowledge/ask").session(session)
                        .contentType("application/json")
                        .content("{\"question\":\"复盘应该怎么做？\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.answered").value(true))
                .andExpect(jsonPath("$.data.capturedToInbox").value(false))
                .andExpect(jsonPath("$.data.citations[0].file").value("复盘方法.md"))
                .andExpect(jsonPath("$.data.citations[0].index").value(1));
    }

    @Test
    void citationsAreNumberedAndSpreadAcrossFiles() throws Exception {
        // 出处列表的编号必须与答案正文里的 [1][2] 对得上，且不能全来自同一篇长笔记 ——
        // 四段都取自同一篇，「归纳」出来的就只是那一篇的摘要，不是知识库对这个问题的回答。
        writeVaultNote("限流设计.md",
                "# 限流设计\n\n## 通道级限流\n\n限流按通道隔离，每个通道一个令牌桶，互不影响。\n\n"
                        + "## 全局限流\n\n全局限流兜住所有通道的总量，防止某一个下游把配额吃光。\n\n"
                        + "## 限流降级\n\n单通道超限时只熔断该通道，核心通道永不降级，其他的按优先级排队。\n");
        writeVaultNote("网关配置.md",
                "# 网关配置\n\n## 限流开关\n\n限流开关在前置网关统一配置，改完立即生效，不用重启。\n");

        mockMvc.perform(post("/api/v1/knowledge/ask").session(session)
                        .contentType("application/json")
                        .content("{\"question\":\"限流\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.citations[0].index").value(1))
                .andExpect(jsonPath("$.data.citations[1].index").value(2));

        String body = mockMvc.perform(post("/api/v1/knowledge/ask").session(session)
                        .contentType("application/json")
                        .content("{\"question\":\"限流\"}"))
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        assertThat(body).contains("\"file\":\"限流设计.md\"").contains("\"file\":\"网关配置.md\"");
        // 同一篇最多两个片段
        int fromSameFile = body.split(java.util.regex.Pattern.quote("\"file\":\"限流设计.md\""), -1).length - 1;
        assertThat(fromSameFile).isEqualTo(2);
    }

    @Test
    void extractiveFallbackListsEveryUsedChunk() throws Exception {
        // 没配 AI 时的兜底以前只摘第一条的前 140 字 —— 读起来就像「只甩了一个文档链接」。
        // 现在把命中的片段按编号列出来，用户至少能看到几处原文各自讲了什么。
        writeVaultNote("限流设计.md",
                "# 限流设计\n\n## 通道级限流\n\n限流按通道隔离，每个通道一个令牌桶，互不影响。\n\n"
                        + "## 全局限流\n\n全局限流兜住所有通道的总量，防止某一个下游把配额吃光。\n");
        writeVaultNote("网关配置.md",
                "# 网关配置\n\n## 限流开关\n\n限流开关在前置网关统一配置，改完立即生效，不用重启。\n");

        mockMvc.perform(post("/api/v1/knowledge/ask").session(session)
                        .contentType("application/json")
                        .content("{\"question\":\"限流\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.llmUsed").value(false))
                .andExpect(jsonPath("$.data.answer", org.hamcrest.Matchers.containsString("根据你的笔记")))
                .andExpect(jsonPath("$.data.answer", org.hamcrest.Matchers.containsString("[1]")))
                .andExpect(jsonPath("$.data.answer", org.hamcrest.Matchers.containsString("[2]")));
    }

    @Test
    void questionMadeOnlyOfCommonWordsIsCapturedInsteadOfAnsweredWithNoise() throws Exception {
        // 剥掉疑问词之后，剩下的内容词如果满库都是（这里「工作」出现在每个片段里），
        // 说明这个问题在这个 Vault 里没有对应的笔记。给出一堆「碰巧含这个词」的链接
        // 比承认没找到更糟 —— 要把问题收进收集箱。
        // ⚠️ 片段数必须超过 MIN_STATS_CHUNKS（20）：低于那个量级时统计量不可靠，
        // 代码会按「任何命中都算数」处理，这条断言就测不到闸门了。
        StringBuilder body = new StringBuilder("# 工作笔记\n\n## 工作安排\n\n每天都在处理工作上的事情，"
                + "把工作记录下来，工作结束之后再看一遍，工作里反复出现的才值得沉淀。\n");
        for (int i = 1; i <= 25; i++) {
            body.append("\n## 工作片段 ").append(i).append("\n\n这是第 ").append(i)
                    .append(" 段工作记录，写工作的方式基本一致，工作内容各有不同但都叫工作。\n");
        }
        writeVaultNote("工作笔记.md", body.toString());

        mockMvc.perform(post("/api/v1/knowledge/ask").session(session)
                        .contentType("application/json")
                        .content("{\"question\":\"什么是番茄工作法\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.answered").value(false))
                .andExpect(jsonPath("$.data.capturedToInbox").value(true));
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
    void hiddenDirectoriesAndHiddenFilesAreNotIndexed() throws Exception {
        // 回归（2026-09-13）：索引原本是 Files.walk(vault).filter(p -> !isHidden(p))。
        // 隐藏内容虽然被过滤掉，但整棵树还是要先枚举一遍 —— 用户 Vault 里塞了一个 Python
        // 虚拟环境（知识体系/.jupymd，5665 个条目）加 .git（2129 个），占 8754 个条目的 90% 以上，
        // 而真正要索引的 .md 只有 344 个。实测容器内一次遍历 10.2 s，这就是「打开知识库要等很久」。
        // 现在改成 preVisitDirectory 里 SKIP_SUBTREE 整棵剪掉。这条断言钉住剪枝的语义边界：
        // 隐藏目录、隐藏文件都不进索引 —— 别为了「顺手支持隐藏文件」把剪枝去掉。
        writeVaultNote("可见笔记.md",
                "# 可见笔记\n\n## 小标题\n\n这是一段足够长的正文，用来确保它被分片收录进索引里。\n");
        Path hiddenDir = vaultDir.resolve(".jupymd").resolve("Lib");
        Files.createDirectories(hiddenDir);
        Files.write(hiddenDir.resolve("包文档.md"),
                "# 包文档\n\n## 说明\n\n虚拟环境里的 markdown 不应该出现在知识库里。\n".getBytes(StandardCharsets.UTF_8));
        Files.write(vaultDir.resolve(".隐藏笔记.md"),
                "# 隐藏笔记\n\n## 说明\n\n以点开头的文件同样不收录进索引。\n".getBytes(StandardCharsets.UTF_8));

        mockMvc.perform(get("/api/v1/knowledge/index").session(session))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.configured").value(true))
                .andExpect(jsonPath("$.data.fileCount").value(1))
                .andExpect(jsonPath("$.data.chunkCount").value(1));
    }

    @Test
    void indexSnapshotIsReusedUntilInvalidated() throws Exception {
        // 缓存契约：TTL 窗口内不再重扫，所以「热路径零磁盘访问」这件事不会被后人改回去。
        // 应用自己的写入会主动 invalidateIndex()（见 writeInboxNote，由下一个用例守着），
        // 这里模拟的是「绕过应用、直接在 Obsidian 里改盘」——它最多晚一个窗口生效。
        writeVaultNote("第一篇.md",
                "# 第一篇\n\n## 内容\n\n第一段足够长的正文内容，用来产生一个可检索的分片。\n");
        mockMvc.perform(get("/api/v1/knowledge/index").session(session))
                .andExpect(jsonPath("$.data.fileCount").value(1));

        writeVaultNote("第二篇.md",
                "# 第二篇\n\n## 内容\n\n第二段足够长的正文内容，用来产生一个可检索的分片。\n");
        mockMvc.perform(get("/api/v1/knowledge/index").session(session))
                .andExpect(jsonPath("$.data.fileCount").value(1));

        vaultService.invalidateIndex();
        mockMvc.perform(get("/api/v1/knowledge/index").session(session))
                .andExpect(jsonPath("$.data.fileCount").value(2));
    }

    @Test
    void writingNoteThroughAppRefreshesIndexImmediately() throws Exception {
        // 应用自己写 Vault 必须主动作废快照：否则刚收藏的笔记要等 TTL 过期才搜得到，
        // 表现就是「知识库问答里找不到我刚存的东西」。
        long first = createInbox("技术方案模板标准结构：背景目标、方案对比、详细设计、容量评估");
        mockMvc.perform(post("/api/v1/inbox/{id}/confirm", first).session(session)
                        .contentType("application/json")
                        .content("{\"category\":\"knowledge\",\"title\":\"技术方案模板结构\"}"))
                .andExpect(status().isOk());
        mockMvc.perform(get("/api/v1/knowledge/index").session(session))
                .andExpect(jsonPath("$.data.fileCount").value(1));

        long second = createInbox("复盘流程说明：先收集事实，再区分事实与判断，最后落地成一条可执行的改进项");
        mockMvc.perform(post("/api/v1/inbox/{id}/confirm", second).session(session)
                        .contentType("application/json")
                        .content("{\"category\":\"knowledge\",\"title\":\"复盘流程说明\"}"))
                .andExpect(status().isOk());
        mockMvc.perform(get("/api/v1/knowledge/index").session(session))
                .andExpect(jsonPath("$.data.fileCount").value(2));
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
