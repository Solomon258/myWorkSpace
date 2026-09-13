package com.icecode.workbench.memo;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
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
class MemoFlowTest {

    private static final String TEST_ROOT = "target/test-workbench/memo-" + UUID.randomUUID().toString();

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
        jdbcTemplate.update("DELETE FROM memo");
        jdbcTemplate.update("DELETE FROM activity_log");
        jdbcTemplate.update("UPDATE app_config SET config_value='true' WHERE config_key='app.initialized'");
        jdbcTemplate.update("UPDATE app_config SET config_value='Asia/Shanghai' WHERE config_key='app.timezone'");
        session = new MockHttpSession();
        session.setAttribute(AuthConstants.SESSION_USER, "tester");
    }

    @Test
    void createsMemoWithAutoUrlTagsAndGroup() throws Exception {
        mockMvc.perform(post("/api/v1/memos").session(session)
                        .contentType("application/json")
                        .content("{\"content\":\"一篇讲双链笔记的文章，值得参考 https://example.com/obsidian-links\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.grp").value("work"))
                .andExpect(jsonPath("$.data.url").value("https://example.com/obsidian-links"))
                .andExpect(jsonPath("$.data.tags[0]").value("链接收藏"));

        mockMvc.perform(post("/api/v1/memos").session(session)
                        .contentType("application/json")
                        .content("{\"content\":\"奶粉2段一罐、湿巾两包，周五前买好\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.grp").value("life"))
                .andExpect(jsonPath("$.data.tags[0]").value("家庭采购"));

        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM activity_log WHERE log_type='memo' AND category='work'", Integer.class)).isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM activity_log WHERE log_type='memo' AND category='life'", Integer.class)).isEqualTo(1);
    }

    /** 自动标题会落库成 memo.title 的正式标题，不能带「…」省略号（历史上照搬了 demo 的 slice(0,24)+'…'）。 */
    @Test
    void autoTitleIsClippedWithoutEllipsis() throws Exception {
        String content = "这是一段超过二十四个字的长备忘内容用来验证自动标题的裁剪行为是否正确无误";
        String expected = content.substring(0, 24);
        mockMvc.perform(post("/api/v1/memos").session(session)
                        .contentType("application/json")
                        .content("{\"content\":\"" + content + "\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.title").value(expected));
        assertThat(jdbcTemplate.queryForObject("SELECT title FROM memo ORDER BY id DESC LIMIT 1", String.class))
                .isEqualTo(expected)
                .doesNotContain("…");
    }

    /**
     * 录入区补了「标题」输入框（2026-09-11）之后，前端会显式传 title —— 这条契约此前没有用例守着。
     * 显式标题必须**原样落库**：既不能被 shortTitle 覆盖，也不能被裁到 24 字。
     * 只有「不传 / null / 纯空白」时才回落到正文前 24 字（由 autoTitleIsClippedWithoutEllipsis 覆盖）。
     */
    @Test
    void explicitTitleIsUsedAsIsAndBlankFallsBackToContent() throws Exception {
        String longTitle = "一个明显超过二十四个字的标题用来确认显式标题不会被裁切掉";
        assertThat(longTitle.length()).as("这条用例要长于自动标题的 24 字上限才有意义").isGreaterThan(24);
        mockMvc.perform(post("/api/v1/memos").session(session)
                        .contentType("application/json")
                        .content("{\"title\":\"" + longTitle + "\",\"content\":\"正文内容\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.title").value(longTitle));

        // 前端「标题留空」时发的是 null，这两条是那种情况的实际入参形态
        mockMvc.perform(post("/api/v1/memos").session(session)
                        .contentType("application/json")
                        .content("{\"title\":null,\"content\":\"只有正文的备忘\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.title").value("只有正文的备忘"));
        mockMvc.perform(post("/api/v1/memos").session(session)
                        .contentType("application/json")
                        .content("{\"title\":\"   \",\"content\":\"标题只打了空格\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.title").value("标题只打了空格"));
    }

    /**
     * 标题上限 200 字必须由后端拦住，且 message 要能指导操作 ——
     * 前端输入框的 maxlength="200" 与这条对齐（两边写歪了就是白填一段再撞 400）。
     */
    @Test
    void overlongTitleIsRejectedWithActionableMessage() throws Exception {
        StringBuilder title = new StringBuilder();
        for (int i = 0; i < 201; i++) title.append("字");
        mockMvc.perform(post("/api/v1/memos").session(session)
                        .contentType("application/json")
                        .content("{\"title\":\"" + title + "\",\"content\":\"正文内容\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(1002))
                .andExpect(jsonPath("$.message").value("备忘标题不能超过 200 字"));
    }

    /**
     * 正文去掉必填（2026-09-12）：速查类备忘往往只有一行标题（「班车 7:20 小区门口」），
     * 强迫用户再往正文里抄一遍只会让人放弃记录。所以「只填标题」必须能存。
     */
    @Test
    void createsMemoWithTitleOnlyAndNoContent() throws Exception {
        mockMvc.perform(post("/api/v1/memos").session(session)
                        .contentType("application/json")
                        .content("{\"title\":\"班车 7:20 小区门口\",\"grp\":\"life\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.title").value("班车 7:20 小区门口"))
                .andExpect(jsonPath("$.data.content").value(""));
        assertThat(jdbcTemplate.queryForObject("SELECT title FROM memo ORDER BY id DESC LIMIT 1", String.class))
                .isEqualTo("班车 7:20 小区门口");
    }

    /**
     * 但标题与正文**不能同时为空**：那会存出一张既没标题也没内容的空卡片，
     * 列表里它占一格、点开什么都没有。这是跨字段约束，注解表达不了，只有 Service 守得住，
     * 所以报错必须写明「写哪一项都行」，而不是笼统的「请求参数不正确」。
     */
    @Test
    void rejectsMemoWithNeitherTitleNorContent() throws Exception {
        for (String body : new String[] {"{}", "{\"title\":\"\"}", "{\"content\":\"\"}",
                "{\"title\":null,\"content\":null}", "{\"title\":\"   \",\"content\":\"  \"}",
                "{\"title\":\"\",\"content\":\"\"}"}) {
            mockMvc.perform(post("/api/v1/memos").session(session)
                            .contentType("application/json").content(body))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value(1002))
                    .andExpect(jsonPath("$.message").value("备忘的标题和正文不能同时为空，至少写一项"));
        }
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM memo", Integer.class)).isEqualTo(0);
    }

    /** 编辑路径与创建路径必须是同一条规则：只守一个入口，另一个入口照样能存出空卡片。 */
    @Test
    void updateKeepsRuleThatAtLeastOneFieldIsFilled() throws Exception {
        long id = createMemo("原标题", "原正文", null, "work");

        // 清空正文、标题还在 => 允许（正文是可空字段了）
        mockMvc.perform(patch("/api/v1/memos/{id}", id).session(session)
                        .contentType("application/json").content("{\"content\":\"\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.content").value(""))
                .andExpect(jsonPath("$.data.title").value("原标题"));

        // 清空标题、正文还在 => 允许，标题回落到正文前 24 字（与创建路径同一套兜底）
        long second = createMemo("待清空的标题", "只有正文的这一条备忘", null, "work");
        mockMvc.perform(patch("/api/v1/memos/{id}", second).session(session)
                        .contentType("application/json").content("{\"title\":\"\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.title").value("只有正文的这一条备忘"));

        // 两项都清空 => 拒绝，且文案能指导操作
        mockMvc.perform(patch("/api/v1/memos/{id}", id).session(session)
                        .contentType("application/json").content("{\"title\":\"\",\"content\":\"\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(1002))
                .andExpect(jsonPath("$.message").value("备忘的标题和正文不能同时为空，至少写一项"));
    }

    @Test
    void searchesByChineseKeywordUrlAndTagWithLiteralSpecials() throws Exception {
        createMemo("公司班车时间表", "早班 7:50 软件园东门发车，晚班 18:30", null, null);
        createMemo("报销 100% 完成", "报销流程已经 100% 走完", null, "work");
        createMemo("字段 A_B 说明", "数据库 A_B 字段含义", null, "work");
        mockMvc.perform(get("/api/v1/memos").param("q", "班车").session(session))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.length()").value(1));
        mockMvc.perform(get("/api/v1/memos").param("q", "%").session(session))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.length()").value(1));
        mockMvc.perform(get("/api/v1/memos").param("q", "_").session(session))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.length()").value(1));
    }

    @Test
    void pinSortsFirstAndArchiveRestores() throws Exception {
        long first = createMemo("普通备忘", "普通内容", null, "work");
        long second = createMemo("置顶备忘", "需要置顶的内容", null, "work");
        mockMvc.perform(post("/api/v1/memos/{id}/pin", second).session(session)).andExpect(status().isOk());
        mockMvc.perform(get("/api/v1/memos").session(session))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].id").value((int) second))
                .andExpect(jsonPath("$.data[0].pinned").value(true));

        mockMvc.perform(post("/api/v1/memos/{id}/archive", first).session(session))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.status").value("archived"));
        mockMvc.perform(get("/api/v1/memos").session(session))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.length()").value(1));
        mockMvc.perform(get("/api/v1/memos").param("archived", "true").session(session))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.length()").value(2));
        mockMvc.perform(post("/api/v1/memos/{id}/archive", first).session(session))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.status").value("active"));
    }

    @Test
    void updatesAndSoftDeletesMemo() throws Exception {
        long id = createMemo("原始标题", "原始内容", null, "life");
        mockMvc.perform(patch("/api/v1/memos/{id}", id).session(session)
                        .contentType("application/json")
                        .content("{\"title\":\"修改后标题\",\"grp\":\"work\",\"tags\":\"运维,值班\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.title").value("修改后标题"))
                .andExpect(jsonPath("$.data.grp").value("work"))
                .andExpect(jsonPath("$.data.tags[0]").value("运维"));
        mockMvc.perform(delete("/api/v1/memos/{id}", id).session(session)).andExpect(status().isOk());
        mockMvc.perform(get("/api/v1/memos").param("archived", "true").session(session))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.length()").value(0));
        assertThat(jdbcTemplate.queryForObject("SELECT deleted FROM memo WHERE id=?", Integer.class, id)).isEqualTo(1);
    }

    @Test
    void searchBenchmarkUnderTwoThousandRows() throws Exception {
        String now = TimeUtil.now("Asia/Shanghai");
        for (int i = 0; i < 2000; i++) {
            jdbcTemplate.update("INSERT INTO memo(title,content,tags,grp,status,created_at,updated_at,is_demo) VALUES (?,?,?,?, 'active', ?, ?, 0)",
                    "批量备忘" + i, "内容" + i, "[\"批量\"]", i % 2 == 0 ? "work" : "life", now, now);
        }
        createMemo("公司班车时间表", "早班 7:50 发车", null, "life");
        long started = System.currentTimeMillis();
        mockMvc.perform(get("/api/v1/memos").param("q", "班车").session(session))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.length()").value(1));
        long elapsed = System.currentTimeMillis() - started;
        assertThat(elapsed).as("2000 条检索耗时应小于 2000ms，1 万条基线在 M9 统一测量").isLessThan(2000);
    }

    private long createMemo(String title, String content, String tags, String grp) {
        String now = TimeUtil.now("Asia/Shanghai");
        jdbcTemplate.update("INSERT INTO memo(title,content,tags,grp,status,created_at,updated_at,is_demo) VALUES (?,?,?,?, 'active', ?, ?, 0)",
                title, content, tags == null ? "[]" : tags, grp == null ? "work" : grp, now, now);
        return jdbcTemplate.queryForObject("SELECT id FROM memo WHERE title=?", Long.class, title).longValue();
    }
}
