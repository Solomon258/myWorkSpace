package com.icecode.workbench.search;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.charset.StandardCharsets;
import java.util.UUID;

import org.hamcrest.Matchers;
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

/**
 * 全局搜索（跨 收录 / 任务 / 日程 / 收藏 / 时间线 + 回收站）。
 *
 * <p>这一组用例守的是设计文档里那几条「不做就一定出 bug」的约定：</p>
 * <ul>
 *   <li><b>回收站命中只能落到回收站页</b>。任务 / 日程 / 收藏页查的都是 {@code deleted=0}，
 *       把已删数据的落点写成原实体页，用户点开就是「点了没反应」——这是正确性问题。</li>
 *   <li><b>待定日程的 {@code target.date} 必须是 null</b>，任何方向都不许补今天（US-4.2）。</li>
 *   <li><b>标点组成的查询串绝不能返回整库</b>。切不出检索词时 WHERE 会退化成无条件，
 *       这是「搜一个逗号，出来 500 条」的成因。</li>
 *   <li><b>截断必须说出来</b>：{@code total} 与 {@code hasMore} 都要给前端。</li>
 * </ul>
 */
@SpringBootTest
@AutoConfigureMockMvc
class SearchFlowTest {

    private static final String TEST_ROOT = "target/test-workbench/search-" + UUID.randomUUID().toString();
    private static final String TZ = "Asia/Shanghai";

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
        for (String table : new String[] {"pomodoro", "daily_plan_item", "wechat_msg_log", "activity_log",
                "task", "schedule_event", "favorite", "knowledge_note", "daily_plan", "inbox_item"}) {
            jdbcTemplate.update("DELETE FROM " + table);
        }
        jdbcTemplate.update("UPDATE app_config SET config_value='true' WHERE config_key='app.initialized'");
        jdbcTemplate.update("UPDATE app_config SET config_value=? WHERE config_key='app.timezone'", TZ);
        session = new MockHttpSession();
        session.setAttribute(AuthConstants.SESSION_USER, "tester");
    }

    /**
     * 五类实体都能搜到，且分组顺序固定。
     *
     * <p>顺序是契约的一部分：跨类型混排会带来「为什么这条排前面」的困惑，
     * 所以类型之间按 任务 → 日程 → 收藏 → 收录 → 时间线 固定，只有组内才按分数排。</p>
     */
    @Test
    void findsEveryEntityKindAndKeepsGroupsInFixedOrder() throws Exception {
        insertTask("限流配额任务", null);
        insertEvent("限流方案评审", "2026-09-20");
        insertFavorite("限流参数收藏", "正文", "work", "active");
        insertInbox("限流的一句话", "pending");
        insertActivity("限流操作记录");

        mockMvc.perform(get("/api/v1/search").param("q", "限流").session(session))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.groups.length()").value(5))
                .andExpect(jsonPath("$.data.groups[0].type").value("task"))
                .andExpect(jsonPath("$.data.groups[1].type").value("event"))
                .andExpect(jsonPath("$.data.groups[2].type").value("favorite"))
                .andExpect(jsonPath("$.data.groups[3].type").value("inbox"))
                .andExpect(jsonPath("$.data.groups[4].type").value("timeline"))
                .andExpect(jsonPath("$.data.totalCount").value(5))
                // 没命中的分组不返回（前端是「有命中才画分组头」）
                .andExpect(jsonPath("$.data.truncated").value(false));
    }

    /** 关键词为空时要说「请输入」，不能默认「搜全部」。 */
    @Test
    void blankQueryTellsUserWhatToDo() throws Exception {
        mockMvc.perform(get("/api/v1/search").param("q", "  ").session(session))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(1002))
                .andExpect(jsonPath("$.message").value(Matchers.containsString("请输入要搜索的关键词")));

        mockMvc.perform(get("/api/v1/search").session(session))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(1002));
    }

    /**
     * 只由标点/空白组成的查询串切不出检索词。
     *
     * <p>如果放它过去，SQL 的 WHERE 会退化成「没有任何条件」，一次搜索返回整库数据 ——
     * 用户输入一个逗号却看到全部记录，会以为搜索坏了。</p>
     */
    @Test
    void punctuationOnlyQueryIsRejectedInsteadOfReturningEverything() throws Exception {
        insertTask("无关任务一", null);
        insertFavorite("无关收藏一", "正文", "work", "active");

        mockMvc.perform(get("/api/v1/search").param("q", "。。。！？").session(session))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(1002))
                .andExpect(jsonPath("$.message").value(Matchers.containsString("没有可检索的文字")));
    }

    /** 超长关键词要报出实际长度与上限，用户才知道要删掉多少。 */
    @Test
    void overlongQueryReportsItsLength() throws Exception {
        StringBuilder longQuery = new StringBuilder();
        for (int i = 0; i < 101; i++) longQuery.append('测');

        mockMvc.perform(get("/api/v1/search").param("q", longQuery.toString()).session(session))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(1002))
                .andExpect(jsonPath("$.message").value(Matchers.allOf(
                        Matchers.containsString("100"), Matchers.containsString("101"))));
    }

    /** 类型写错时要把合法取值列出来，否则调用方只能靠猜。 */
    @Test
    void unknownTypeListsSupportedOnes() throws Exception {
        mockMvc.perform(get("/api/v1/search").param("q", "限流").param("types", "note").session(session))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(1002))
                .andExpect(jsonPath("$.message").value(Matchers.allOf(
                        Matchers.containsString("note"), Matchers.containsString("timeline"))));
    }

    @Test
    void invalidScopeListsAllowedValues() throws Exception {
        mockMvc.perform(get("/api/v1/search").param("q", "限流").param("scope", "recent").session(session))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(1002))
                .andExpect(jsonPath("$.message").value(Matchers.allOf(
                        Matchers.containsString("all"), Matchers.containsString("active"))));
    }

    /**
     * 已删除的记录只出现在「回收站」组，且落点是回收站页。
     *
     * <p>这是本功能最容易写错的一处：把已删任务留在「任务」组里，
     * 用户会以为任务页坏了（那页面上确实看不到它）；落点写成任务页，
     * 点开就是「点了没反应」。两件事必须一起对。</p>
     */
    @Test
    void deletedRecordAppearsOnlyInTrashGroupAndPointsAtTrashTab() throws Exception {
        insertTask("限流活任务", null);
        long deletedId = insertDeletedTask("限流已删任务");

        mockMvc.perform(get("/api/v1/search").param("q", "限流").session(session))
                .andExpect(status().isOk())
                // 任务组只装活数据
                .andExpect(jsonPath("$.data.groups[0].type").value("task"))
                .andExpect(jsonPath("$.data.groups[0].total").value(1))
                // 回收站独立成组、沉到最后
                .andExpect(jsonPath("$.data.groups[1].type").value("trash"))
                .andExpect(jsonPath("$.data.groups[1].items[0].id").value(deletedId))
                .andExpect(jsonPath("$.data.groups[1].items[0].deleted").value(true))
                // entity 说明它「本来是什么」，target.tab 说明「点开去哪儿」——两者不是一回事
                .andExpect(jsonPath("$.data.groups[1].items[0].entity").value("task"))
                .andExpect(jsonPath("$.data.groups[1].items[0].target.tab").value("trash"));
    }

    /** 用户要「只看活数据」时，回收站整组消失（即使 chips 里还点着回收站）。 */
    @Test
    void scopeActiveHidesTrashGroupEntirely() throws Exception {
        insertTask("限流活任务", null);
        insertDeletedTask("限流已删任务");

        mockMvc.perform(get("/api/v1/search").param("q", "限流").param("scope", "active").session(session))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.groups.length()").value(1))
                .andExpect(jsonPath("$.data.groups[0].type").value("task"))
                .andExpect(jsonPath("$.data.totalCount").value(1));
    }

    /** 组内排序：标题命中要排在正文命中之前，否则「搜到的第一条总是最不相关的」。 */
    @Test
    void titleHitRanksAboveBodyHit() throws Exception {
        long bodyHit = insertTask("无关任务", "备注里才提到限流");
        long titleHit = insertTask("限流方案", null);

        mockMvc.perform(get("/api/v1/search").param("q", "限流").session(session))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.groups[0].total").value(2))
                .andExpect(jsonPath("$.data.groups[0].items[0].id").value(titleHit))
                .andExpect(jsonPath("$.data.groups[0].items[0].matchField").value("title"))
                .andExpect(jsonPath("$.data.groups[0].items[0].matchKind").value("exact"))
                .andExpect(jsonPath("$.data.groups[0].items[1].id").value(bodyHit));
    }

    /** 多个词之间是 AND：搜两个词应该更准，不能更宽。 */
    @Test
    void multipleTermsMustAllMatch() throws Exception {
        long both = insertTask("限流与配额都要改", null);
        insertTask("只提限流的任务", null);

        mockMvc.perform(get("/api/v1/search").param("q", "限流 配额").session(session))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.groups[0].total").value(1))
                .andExpect(jsonPath("$.data.groups[0].items[0].id").value(both));
    }

    /** 截断时必须给出「还有多少条」，否则用户会以为「就这几条」。 */
    @Test
    void truncatedGroupReportsRemainingCount() throws Exception {
        for (int i = 0; i < 7; i++) insertTask("限流任务" + i, null);

        mockMvc.perform(get("/api/v1/search").param("q", "限流").param("limit", "5").session(session))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.groups[0].total").value(7))
                .andExpect(jsonPath("$.data.groups[0].items.length()").value(5))
                .andExpect(jsonPath("$.data.groups[0].hasMore").value(true))
                .andExpect(jsonPath("$.data.truncated").value(true));
    }

    @Test
    void limitAboveCeilingIsRejected() throws Exception {
        mockMvc.perform(get("/api/v1/search").param("q", "限流").param("limit", "99").session(session))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(1002))
                .andExpect(jsonPath("$.message").value(Matchers.containsString("20")));
    }

    /** `%` 不能被当成 SQL 通配符，否则搜「100%」会返回全库。 */
    @Test
    void percentIsNotTreatedAsWildcard() throws Exception {
        insertTask("100% 覆盖率", null);
        insertTask("100 条任务", null);
        insertTask("完全无关的记录", null);

        mockMvc.perform(get("/api/v1/search").param("q", "100%").session(session))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.groups[0].total").value(2));
    }

    /** 日程结果要带上它那一天，前端才能先切日期再高亮。 */
    @Test
    void eventResultCarriesItsDateForJumping() throws Exception {
        insertEvent("限流方案评审", "2026-09-20");

        mockMvc.perform(get("/api/v1/search").param("q", "限流").session(session))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.groups[0].type").value("event"))
                .andExpect(jsonPath("$.data.groups[0].items[0].target.tab").value("schedule"))
                .andExpect(jsonPath("$.data.groups[0].items[0].target.date").value("2026-09-20"));
    }

    /**
     * 待定日程（{@code event_date IS NULL}，US-4.2）的落点日期必须是 null。
     *
     * <p>补一个今天进去的话，前端会跳到「今天」而那条日程其实在待定区，表现为「点了没反应」；
     * 更糟的是「任何方向都不隐式补今天」这条领域规则会被悄悄破掉。</p>
     */
    @Test
    void pendingEventKeepsNullDateSoFrontendScrollsToPendingArea() throws Exception {
        insertEvent("限流方案对齐（时间没定）", null);

        mockMvc.perform(get("/api/v1/search").param("q", "限流").session(session))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.groups[0].items[0].target.tab").value("schedule"))
                .andExpect(jsonPath("$.data.groups[0].items[0].target.date").doesNotExist())
                .andExpect(jsonPath("$.data.groups[0].items[0].meta.date").doesNotExist());
    }

    /** 收藏结果要带上所属空间：空间不对，前端切过去也找不到那一行。 */
    @Test
    void favoriteResultCarriesItsSpace() throws Exception {
        insertFavorite("限流参数", "正文", "life", "active");

        mockMvc.perform(get("/api/v1/search").param("q", "限流").session(session))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.groups[0].items[0].target.tab").value("favorites"))
                .andExpect(jsonPath("$.data.groups[0].items[0].target.grp").value("life"));
    }

    /** 时间线结果要带上那一天：那天可能是折叠着的，不先展开就高亮等于「点了没反应」。 */
    @Test
    void timelineResultCarriesTheDayItHappened() throws Exception {
        insertActivity("限流压测完成");

        mockMvc.perform(get("/api/v1/search").param("q", "限流").session(session))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.groups[0].type").value("timeline"))
                .andExpect(jsonPath("$.data.groups[0].items[0].target.tab").value("timeline"))
                .andExpect(jsonPath("$.data.groups[0].items[0].target.day")
                        .value(TimeUtil.today(TZ)));
    }

    /**
     * 「收录」这一类的范围 = 待整理 + 待确认 + 整理失败（用户 2026-09-12 确认）。
     *
     * <p>失败条目特别容易漏：它的 {@code status='failed'}，既不是 pending 也不是 processed，
     * 只按这两个状态过滤就会让「整理失败但还没重试」的条目在搜索里彻底找不到。</p>
     */
    @Test
    void inboxSearchCoversPendingProcessedAndFailed() throws Exception {
        insertInbox("限流待整理", "pending");
        insertInbox("限流待确认", "processed");
        insertInbox("限流整理失败", "failed");

        mockMvc.perform(get("/api/v1/search").param("q", "限流").session(session))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.groups[0].type").value("inbox"))
                .andExpect(jsonPath("$.data.groups[0].total").value(3));
    }

    /** 已归档的收录不在检索范围内（用户明确说的是「待整理 / 待确认」）。 */
    @Test
    void archivedInboxIsOutOfScope() throws Exception {
        insertInbox("限流已归档条目", "archived");

        mockMvc.perform(get("/api/v1/search").param("q", "限流").session(session))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.groups.length()").value(0))
                .andExpect(jsonPath("$.data.totalCount").value(0));
    }

    /** 搜索是纯读取：不改任何业务数据，也不写时间线（搜索不是业务动作）。 */
    @Test
    void searchDoesNotChangeDataAndDoesNotWriteTimeline() throws Exception {
        long taskId = insertTask("限流任务", null);
        int logsBefore = count("activity_log");

        mockMvc.perform(get("/api/v1/search").param("q", "限流").session(session))
                .andExpect(status().isOk());
        mockMvc.perform(get("/api/v1/search").param("q", "限流").param("scope", "all").session(session))
                .andExpect(status().isOk());

        assertThat(count("activity_log")).isEqualTo(logsBefore);
        assertThat(jdbcTemplate.queryForObject("SELECT deleted FROM task WHERE id=?", Integer.class, taskId))
                .isEqualTo(0);
    }

    /** 内容里带引号/斜杠也不能让搜索崩掉或漏掉（真实标题里就有「复盘"双十一"」这种）。 */
    @Test
    void quotesAndSlashesInContentAreSearchable() throws Exception {
        insertTask("复盘\"双十一\"的限流", null);
        insertFavorite("路径 C:\\notes\\限流.md", "正文", "work", "active");

        mockMvc.perform(get("/api/v1/search").param("q", "限流").session(session))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.totalCount").value(2));

        String body = mockMvc.perform(get("/api/v1/search").param("q", "限流").session(session))
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        assertThat(body).contains("复盘");
    }

    /** 中文响应体要显式按 UTF-8 解码，否则断言会假红。 */
    @Test
    void groupLabelsAreChinese() throws Exception {
        insertTask("限流任务", null);

        String body = mockMvc.perform(get("/api/v1/search").param("q", "限流").session(session))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);

        assertThat(body).contains("任务").contains("\"query\":\"限流\"");
    }

    /**
     * 回收站里的条目不再套业务状态过滤：它已经被删了，不论原本是 pending 还是 archived，
     * 都属于「已删数据」。用户选的是「全部含回收站」，这类必须能搜到、且能跳去恢复。
     */
    @Test
    void deletedArchivedInboxIsStillSearchableInTrashGroup() throws Exception {
        String now = TimeUtil.now(TZ);
        jdbcTemplate.update("INSERT INTO inbox_item(raw_content, source, status, created_at, updated_at, deleted, deleted_at)"
                + " VALUES ('限流已归档又被删', 'web', 'archived', ?, ?, 1, ?)", now, now, now);

        mockMvc.perform(get("/api/v1/search").param("q", "限流").session(session))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.groups.length()").value(1))
                .andExpect(jsonPath("$.data.groups[0].type").value("trash"))
                .andExpect(jsonPath("$.data.groups[0].total").value(1))
                .andExpect(jsonPath("$.data.groups[0].items[0].target.tab").value("trash"));
    }

    /**
     * 语义联想词以「联想命中」的身份合并进结果，且排在精确命中之后。
     *
     * <p>分数减半 + 排在后面，是为了让「像」永远不压过「是」：同分的话，
     * 结果页第一条经常变成一条用户根本没搜过的词，「为什么这条排第一」的困惑就是这么来的。</p>
     */
    @Test
    void expandedTermsMergeInAsSemanticHitsAndRankBelowExactOnes() throws Exception {
        long exact = insertTask("限流方案", null);
        long semantic = insertTask("速率限制说明", null);

        mockMvc.perform(get("/api/v1/search").param("q", "限流").session(session))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.groups[0].total").value(1))
                .andExpect(jsonPath("$.data.semanticState").value("off"));

        mockMvc.perform(get("/api/v1/search").param("q", "限流").param("expand", "速率").session(session))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.groups[0].total").value(2))
                .andExpect(jsonPath("$.data.groups[0].items[0].id").value(exact))
                .andExpect(jsonPath("$.data.groups[0].items[0].matchKind").value("exact"))
                .andExpect(jsonPath("$.data.groups[0].items[1].id").value(semantic))
                .andExpect(jsonPath("$.data.groups[0].items[1].matchKind").value("semantic"))
                .andExpect(jsonPath("$.data.expandedTerms[0]").value("速率"))
                .andExpect(jsonPath("$.data.semanticState").value("ready"));
    }

    /** 扩展词命中的若已经被关键字命中，不能重复出现（否则同一条记录在列表里显示两遍）。 */
    @Test
    void expandedTermsDoNotDuplicateRecordsAlreadyMatchedByKeyword() throws Exception {
        insertTask("限流方案", null);

        mockMvc.perform(get("/api/v1/search").param("q", "限流").param("expand", "限流,限流方案,限流").session(session))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.groups[0].total").value(1))
                .andExpect(jsonPath("$.data.groups[0].items[0].matchKind").value("exact"));
    }

    /** 扩展词里的空白与大小写要按同一口径清洗，不能因为多打一个空格就换一批结果。 */
    @Test
    void expandTermsAreTrimmedAndDeduped() throws Exception {
        insertTask("限流与 QPS", null);
        insertTask("Rate Limit 说明", null);

        mockMvc.perform(get("/api/v1/search").param("q", "限流")
                        .param("expand", "  rate limit ,, rate limit , ").session(session))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.groups[0].total").value(2))
                .andExpect(jsonPath("$.data.expandedTerms.length()").value(1))
                .andExpect(jsonPath("$.data.expandedTerms[0]").value("rate limit"));
    }

    /** 搜索结果会露出已删记录的原文，必须和其它业务接口一样要求登录。 */
    @Test
    void requiresLogin() throws Exception {
        mockMvc.perform(get("/api/v1/search").param("q", "限流"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value(2002));
    }

    // ---------- helpers ----------

    private int count(String table) {
        Integer value = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM " + table, Integer.class);
        return value == null ? 0 : value.intValue();
    }

    private long insertTask(String title, String note) {
        String now = TimeUtil.now(TZ);
        jdbcTemplate.update("INSERT INTO task(title, note, priority, status, created_at, updated_at, deleted)"
                + " VALUES (?,?, 'P2', 'todo', ?, ?, 0)", title, note, now, now);
        return jdbcTemplate.queryForObject("SELECT id FROM task WHERE title=?", Long.class, title);
    }

    private long insertDeletedTask(String title) {
        String now = TimeUtil.now(TZ);
        jdbcTemplate.update("INSERT INTO task(title, priority, status, created_at, updated_at, deleted, deleted_at)"
                + " VALUES (?, 'P2', 'todo', ?, ?, 1, ?)", title, TimeUtil.daysAgo(TZ, 40), now, now);
        return jdbcTemplate.queryForObject("SELECT id FROM task WHERE title=?", Long.class, title);
    }

    private long insertEvent(String title, String date) {
        String now = TimeUtil.now(TZ);
        jdbcTemplate.update("INSERT INTO schedule_event(title, event_type, event_date, created_at, updated_at, deleted)"
                + " VALUES (?, 'meeting', ?, ?, ?, 0)", title, date, now, now);
        return jdbcTemplate.queryForObject("SELECT id FROM schedule_event WHERE title=?", Long.class, title);
    }

    private long insertFavorite(String title, String content, String grp, String status) {
        String now = TimeUtil.now(TZ);
        jdbcTemplate.update("INSERT INTO favorite(title, content, grp, status, pinned, created_at, updated_at, deleted)"
                + " VALUES (?,?,?,?,0,?,?,0)", title, content, grp, status, now, now);
        return jdbcTemplate.queryForObject("SELECT id FROM favorite WHERE title=?", Long.class, title);
    }

    private void insertInbox(String raw, String status) {
        String now = TimeUtil.now(TZ);
        jdbcTemplate.update("INSERT INTO inbox_item(raw_content, source, status, created_at, updated_at, deleted)"
                + " VALUES (?, 'web', ?, ?, ?, 0)", raw, status, now, now);
    }

    private void insertActivity(String content) {
        jdbcTemplate.update("INSERT INTO activity_log(log_type, content, created_at, is_demo) VALUES ('task',?,?,0)",
                content, TimeUtil.now(TZ));
    }
}
