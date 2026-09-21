package com.icecode.workbench.collect;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import com.icecode.workbench.auth.AuthConstants;

/**
 * 「粘贴链接 → 落 Obsidian + 进知识库」的端到端测试。
 *
 * <p>这里把 {@link DedaoShareParser} 换成替身，只验证<b>落盘与建档</b>这一段：
 * 目录怎么选、文件长什么样、重复分享怎么办、目录名想逃出 Vault 时会发生什么。
 * 真正的抓取与解析由 {@link DedaoShareParserTest} 用固定样本覆盖，两边都不碰网络。
 */
@SpringBootTest
@AutoConfigureMockMvc
class ArticleCollectTest {

    private static final String TEST_ROOT =
            "target/test-workbench/collect-" + UUID.randomUUID().toString();
    private static final String URL = "https://www.dedao.cn/share/packet?packetId=abc";
    private static final String TITLE = "06｜问答：孩子沉迷电子产品，怎么办？";

    @TempDir
    Path vaultDir;

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("workbench.data-dir", () -> TEST_ROOT + "/data");
        registry.add("workbench.home-dir", () -> TEST_ROOT);
    }

    @Autowired private MockMvc mockMvc;
    @Autowired private JdbcTemplate jdbcTemplate;
    @MockBean private DedaoShareParser dedaoShareParser;
    private MockHttpSession session;

    @BeforeEach
    void setUp() {
        jdbcTemplate.update("DELETE FROM knowledge_note");
        jdbcTemplate.update("DELETE FROM activity_log");
        jdbcTemplate.update("UPDATE app_config SET config_value='true' WHERE config_key='app.initialized'");
        jdbcTemplate.update("UPDATE app_config SET config_value='Asia/Shanghai' WHERE config_key='app.timezone'");
        jdbcTemplate.update("UPDATE app_config SET config_value='false' WHERE config_key='ai.enabled'");
        // 每个用例都从「没配得到 Cookie」开始：Cookie 是跨用例共享的库状态，
        // 不清理就会让「没配 Cookie 时该提示什么」的断言读到上一条用例插进去的值
        // （实测踩到：两条用例互相污染，报出来的红指向了产品代码，其实是测试自己没隔离）。
        jdbcTemplate.update("DELETE FROM app_config WHERE config_key='dedao.cookie'");
        jdbcTemplate.update("UPDATE app_config SET config_value=? WHERE config_key='obsidian.vault_path'",
                vaultDir.toAbsolutePath().toString());
        session = new MockHttpSession();
        session.setAttribute(AuthConstants.SESSION_USER, "tester");

        when(dedaoShareParser.supports(anyString())).thenReturn(true);
        // 注意现在是**两参**重载：collect 会把配置里的得到 Cookie 一并传下去。
        // 第二参用 any() 而不是 anyString() —— 没配 Cookie 时传的是 null，anyString() 匹配不到。
        when(dedaoShareParser.parse(anyString(), any())).thenReturn(new ArticleMeta(
                "dedao", TITLE, "吴军·教育的方法50讲", "吴军", URL, "我是吴军，欢迎你开启这场教育的理性探索之旅。"));
    }

    @Test
    void writesNoteIntoCourseFolderAndRecordsKnowledge() throws Exception {
        collect(URL)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.title").value(TITLE))
                .andExpect(jsonPath("$.data.collection").value("吴军·教育的方法50讲"))
                .andExpect(jsonPath("$.data.author").value("吴军"))
                .andExpect(jsonPath("$.data.platform").value("得到"))
                .andExpect(jsonPath("$.data.syncStatus").value("synced"))
                .andExpect(jsonPath("$.data.vaultPath")
                        .value("知识体系/得到/吴军·教育的方法50讲/" + TITLE + ".md"));

        Path note = vaultDir.resolve("知识体系/得到/吴军·教育的方法50讲/" + TITLE + ".md");
        assertThat(Files.exists(note)).isTrue();
        String markdown = new String(Files.readAllBytes(note), StandardCharsets.UTF_8);

        // 与用户 Vault 里既有 46 篇的形态对齐：只有 tags、正文首行原文链接、不写 H1
        assertThat(markdown).contains("tags:");
        assertThat(markdown).contains("  - 吴军");
        assertThat(markdown).contains("  - 教育的方法50讲");
        assertThat(markdown).contains("  - 得到");
        assertThat(markdown).contains("[原文链接](" + URL + ")");
        assertThat(markdown).contains("我是吴军，欢迎你开启这场教育的理性探索之旅。");
        assertThat(markdown).doesNotContain("created:");
        assertThat(markdown).doesNotContain("source: personal-workbench");
        assertThat(markdown).doesNotContain("# " + TITLE);

        assertThat(jdbcTemplate.queryForObject(
                "SELECT sync_status FROM knowledge_note ORDER BY id DESC LIMIT 1", String.class))
                .isEqualTo("synced");
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM activity_log WHERE log_type='knowledge'", Integer.class)).isEqualTo(1);
    }

    @Test
    void repeatedShareCreatesSecondFileInsteadOfOverwriting() throws Exception {
        collect(URL).andExpect(status().isOk());
        collect(URL).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.vaultPath")
                        .value("知识体系/得到/吴军·教育的方法50讲/" + TITLE + "-2.md"));

        Path folder = vaultDir.resolve("知识体系/得到/吴军·教育的方法50讲");
        try (java.util.stream.Stream<Path> files = Files.list(folder)) {
            assertThat(files.filter(p -> p.getFileName().toString().endsWith(".md")).count()).isEqualTo(2L);
        }
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM knowledge_note", Integer.class)).isEqualTo(2);
    }

    @Test
    void refusesToWriteOutsideVaultWhenCourseNameContainsTraversal() throws Exception {
        when(dedaoShareParser.parse(anyString(), any())).thenReturn(new ArticleMeta(
                "dedao", TITLE, "../../../evil", "吴军", URL, "正文"));

        collect(URL).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.vaultPath").value("知识体系/得到/evil/" + TITLE + ".md"));

        assertThat(Files.exists(vaultDir.resolve("知识体系/得到/evil/" + TITLE + ".md"))).isTrue();
        assertThat(Files.exists(vaultDir.getParent().resolve("evil"))).isFalse();
        assertThat(Files.exists(vaultDir.getParent().getParent().resolve("evil"))).isFalse();
    }

    @Test
    void acceptsWholeShareTextAndPicksTheLinkOut() throws Exception {
        String shareText = "我分享给你一个知识红包，限量20个，邀请你免费学 https://www.dedao.cn/share/packet?packetId=abc 复制此链接打开得到";
        collect(shareText).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.syncStatus").value("synced"));
    }

    @Test
    void rejectsUnsupportedPlatformWithActionableCode() throws Exception {
        when(dedaoShareParser.supports(anyString())).thenReturn(false);

        collect("https://mp.weixin.qq.com/s/abcdef")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(3009));
    }

    @Test
    void rejectsTextWithoutAnyLink() throws Exception {
        collect("今天看了一篇很好的文章")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(1002));
    }

    @Test
    void refusesToWriteTrialOnlyArticle() throws Exception {
        // 得到分享页对匿名请求只下发约 20% 正文。旧实现会照单全收，结果是用户 Vault 里
        // 多出一篇「看不出缺了一半」的笔记 —— 2026-09-20 用户反馈的正是这个。
        when(dedaoShareParser.parse(anyString(), any())).thenReturn(new ArticleMeta(
                "dedao", TITLE, "吴军·教育的方法50讲", "吴军", URL, "我是吴军。", true));

        collect(URL)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(3017));

        // 最关键的一条：一个字都不许落盘 —— 半篇文章比这次失败更难收拾。
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM knowledge_note", Integer.class)).isZero();
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM activity_log", Integer.class)).isZero();
        assertThat(Files.exists(vaultDir.resolve("知识体系/得到/吴军·教育的方法50讲/" + TITLE + ".md"))).isFalse();
    }

    @Test
    void trialOnlyFailureTellsUserWhereToConfigureCookie() throws Exception {
        String body = trialOnlyResponse();

        // 用户看到的必须是「去哪儿配什么」，而不是一句笼统的「解析失败」。
        assertThat(body).contains("没有收藏").contains("得到登录 Cookie");
    }

    @Test
    void trialOnlyFailureWithCookieConfiguredSaysToRefreshIt() throws Exception {
        // 配了 Cookie 却仍拿不到全文 = 最可能的原因是它失效了。
        // 此时若还让用户「去设置里配 Cookie」，他会以为设置没保存上 —— 得说清是「换一份新的」。
        jdbcTemplate.update("INSERT OR REPLACE INTO app_config(config_key, config_value, updated_at) "
                + "VALUES('dedao.cookie','token=stale; sid=old','2026-09-20T00:00:00')");

        String body = trialOnlyResponse();

        assertThat(body).contains("Cookie").contains("重新登录");
        assertThat(body).doesNotContain("得到登录 Cookie");
    }

    @Test
    void passesConfiguredCookieDownToParser() throws Exception {
        // 「配了却仍只有试读」的头号嫌疑就是 Cookie 没真的接到抓取层
        // （本项目的老毛病：接口写好没接上 = 功能不存在），所以在这里钉住。
        jdbcTemplate.update("INSERT OR REPLACE INTO app_config(config_key, config_value, updated_at) "
                + "VALUES('dedao.cookie','token=abc; sid=xyz','2026-09-20T00:00:00')");
        org.mockito.ArgumentCaptor<String> cookie = org.mockito.ArgumentCaptor.forClass(String.class);

        collect(URL).andExpect(status().isOk());

        org.mockito.Mockito.verify(dedaoShareParser).parse(anyString(), cookie.capture());
        assertThat(cookie.getValue()).isEqualTo("token=abc; sid=xyz");
    }

    /** 让替身返回「只有试读」，再取回响应体原文（中文必须显式按 UTF-8 读，否则断言会假红）。 */
    private String trialOnlyResponse() throws Exception {
        when(dedaoShareParser.parse(anyString(), any())).thenReturn(new ArticleMeta(
                "dedao", TITLE, "吴军·教育的方法50讲", "吴军", URL, "我是吴军。", true));
        return mockMvc.perform(post("/api/v1/collect/article").session(session)
                        .contentType("application/json")
                        .content("{\"content\":\"" + URL + "\"}"))
                .andExpect(status().isBadRequest())
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
    }

    @Test
    void extractsLinkFromShareText() {
        assertThat(ArticleCollectService.extractUrl("看看这个 https://a.com/b，很好"))
                .isEqualTo("https://a.com/b");
        assertThat(ArticleCollectService.extractUrl("https://a.com/b?x=1&y=2 复制打开"))
                .isEqualTo("https://a.com/b?x=1&y=2");
        assertThat(ArticleCollectService.extractUrl("没有链接")).isEmpty();
        assertThat(ArticleCollectService.extractUrl(null)).isEmpty();
    }

    @Test
    void agentEndpointAcceptsCallWithoutLogin() throws Exception {
        // 本机 Agent（Hermes / OpenClaw）拿不到浏览器的登录态，这个端点必须免登录可用。
        // 刻意不传 session。
        mockMvc.perform(post("/api/v1/collect/agent")
                        .contentType("application/json")
                        .content("{\"content\":\"" + URL + "\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.syncStatus").value("synced"))
                .andExpect(jsonPath("$.data.vaultPath")
                        .value("知识体系/得到/吴军·教育的方法50讲/" + TITLE + ".md"));
    }

    @Test
    void articleEndpointStillRequiresLogin() throws Exception {
        // 反向断言：免登录只放开了 /agent 这一个路径，/article 仍然必须登录 ——
        // 否则「加一个本机入口」就顺带把网页端也敞开了。
        mockMvc.perform(post("/api/v1/collect/article")
                        .contentType("application/json")
                        .content("{\"content\":\"" + URL + "\"}"))
                .andExpect(status().isUnauthorized());
    }

    private org.springframework.test.web.servlet.ResultActions collect(String content) throws Exception {
        String body = "{\"content\":\"" + content.replace("\\", "\\\\").replace("\"", "\\\"") + "\"}";
        return mockMvc.perform(post("/api/v1/collect/article").session(session)
                .contentType("application/json").content(body));
    }
}
