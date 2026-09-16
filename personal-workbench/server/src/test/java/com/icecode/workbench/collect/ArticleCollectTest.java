package com.icecode.workbench.collect;

import static org.assertj.core.api.Assertions.assertThat;
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
        jdbcTemplate.update("UPDATE app_config SET config_value=? WHERE config_key='obsidian.vault_path'",
                vaultDir.toAbsolutePath().toString());
        session = new MockHttpSession();
        session.setAttribute(AuthConstants.SESSION_USER, "tester");

        when(dedaoShareParser.supports(anyString())).thenReturn(true);
        when(dedaoShareParser.parse(anyString())).thenReturn(new ArticleMeta(
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
        when(dedaoShareParser.parse(anyString())).thenReturn(new ArticleMeta(
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
