package com.icecode.workbench.trash;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
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

/**
 * 回收站（US-1.5「软删除，30 天内可恢复」）。
 *
 * <p>这一组用例守的是「承诺必须能兑现」：文档从第一版就写着「30 天内可恢复」，
 * 但此前既没有恢复接口、界面上也没有入口，被标记删除的记录只是查询时被
 * {@code WHERE deleted=0} 过滤掉——用户按文档去等一个不存在的功能。
 * 所以这里不只测「能恢复」，也测**恢复失败的三种原因各自说得清**
 * （不存在 / 没被删 / 过了保留期），避免又退化成一句「操作失败」。</p>
 */
@SpringBootTest
@AutoConfigureMockMvc
class TrashFlowTest {

    private static final String TEST_ROOT = "target/test-workbench/trash-" + UUID.randomUUID().toString();
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
        // 先删子表再删父表：task / schedule_event / memo / knowledge_note 都外键引用 inbox_item
        for (String table : new String[] {"pomodoro", "daily_plan_item", "wechat_msg_log", "activity_log",
                "task", "schedule_event", "memo", "knowledge_note", "daily_plan", "inbox_item"}) {
            jdbcTemplate.update("DELETE FROM " + table);
        }
        jdbcTemplate.update("UPDATE app_config SET config_value='true' WHERE config_key='app.initialized'");
        jdbcTemplate.update("UPDATE app_config SET config_value=? WHERE config_key='app.timezone'", TZ);
        session = new MockHttpSession();
        session.setAttribute(AuthConstants.SESSION_USER, "tester");
    }

    @Test
    void listsAllFiveEntityKindsDeletedWithinRetentionWindow() throws Exception {
        insertDeletedTask("删掉的任务");
        insertDeletedEvent("删掉的日程");
        insertDeletedMemo("删掉的备忘");
        insertDeletedKnowledge("删掉的知识");
        insertDeletedInbox("删掉的收集箱条目");

        mockMvc.perform(get("/api/v1/trash").session(session))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.length()").value(5))
                .andExpect(jsonPath("$.data[*].type", containsInAnyOrder(
                        "inbox", "task", "event", "memo", "knowledge")))
                // 刚删的还剩满 30 天
                .andExpect(jsonPath("$.data[*].daysLeft", containsInAnyOrder(30, 30, 30, 30, 30)));
    }

    /**
     * V7 之前删掉的记录没有 deleted_at（那一列是后加的），只有 updated_at 能反映删除时刻。
     * 若只认 deleted_at，这批历史数据会瞬间「超期消失」——等于迁移把用户的删除记录变成了不可恢复。
     */
    @Test
    void legacyDeletedRowWithoutDeletedAtFallsBackToUpdatedAt() throws Exception {
        String twoDaysAgo = TimeUtil.daysAgo(TZ, 2);
        jdbcTemplate.update("INSERT INTO task(title, priority, status, created_at, updated_at, deleted)"
                + " VALUES (?, 'P2', 'todo', ?, ?, 1)", "迁移前删掉的任务", TimeUtil.daysAgo(TZ, 40), twoDaysAgo);
        long id = jdbcTemplate.queryForObject("SELECT id FROM task WHERE title='迁移前删掉的任务'", Long.class);

        mockMvc.perform(get("/api/v1/trash").session(session))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(1))
                .andExpect(jsonPath("$.data[0].id").value(id))
                .andExpect(jsonPath("$.data[0].deletedAt").value(twoDaysAgo))
                .andExpect(jsonPath("$.data[0].daysLeft").value(28));
    }

    @Test
    void expiredRecordIsHiddenFromListAndExplainsWhyItCannotBeRestored() throws Exception {
        long id = insertDeletedTask("超期任务", TimeUtil.daysAgo(TZ, 31));

        mockMvc.perform(get("/api/v1/trash").session(session))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(0));

        mockMvc.perform(post("/api/v1/trash/task/{id}/restore", id).session(session))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(1002))
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("超过 30 天保留期")));
    }

    @Test
    void restoreBringsRecordBackAndRecordsItOnTheTimeline() throws Exception {
        long id = insertDeletedTask("要恢复的任务");

        mockMvc.perform(post("/api/v1/trash/task/{id}/restore", id).session(session))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.type").value("task"))
                .andExpect(jsonPath("$.data.id").value(id))
                .andExpect(jsonPath("$.data.title").value("要恢复的任务"));

        // 恢复 = 翻转回未删除，并清掉 deleted_at：留着它会让 V7 的
        // CHECK (deleted_at IS NULL OR deleted=1) 直接拒绝这次更新。
        assertThat(jdbcTemplate.queryForObject("SELECT deleted FROM task WHERE id=?", Integer.class, id)).isEqualTo(0);
        assertThat(jdbcTemplate.queryForObject("SELECT deleted_at FROM task WHERE id=?", String.class, id)).isNull();

        // 回到业务列表里才算真的恢复（只改标记位而列表不带它，等于没恢复）
        mockMvc.perform(get("/api/v1/tasks").session(session))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(1));

        // 从回收站恢复也是操作流水的一部分，否则用户事后查不到「什么时候把它捞回来的」
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM activity_log WHERE content LIKE '%从回收站恢复「要恢复的任务」%'",
                Integer.class)).isEqualTo(1);

        mockMvc.perform(get("/api/v1/trash").session(session))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(0));
    }

    /** 待定日程（event_date 为 NULL，US-4.2）恢复后必须还是待定，不能被恢复动作塞上一个日期。 */
    @Test
    void restoringPendingEventKeepsItPendingInsteadOfInventingADate() throws Exception {
        String now = TimeUtil.now(TZ);
        jdbcTemplate.update("INSERT INTO schedule_event(title, event_type, event_date, created_at, updated_at, deleted, deleted_at)"
                + " VALUES ('和业务对齐', 'meeting', NULL, ?, ?, 1, ?)", now, now, now);
        long id = jdbcTemplate.queryForObject("SELECT id FROM schedule_event WHERE title='和业务对齐'", Long.class);

        mockMvc.perform(post("/api/v1/trash/event/{id}/restore", id).session(session))
                .andExpect(status().isOk());

        assertThat(jdbcTemplate.queryForObject(
                "SELECT event_date FROM schedule_event WHERE id=?", String.class, id)).isNull();
        mockMvc.perform(get("/api/v1/events/pending").session(session))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(1))
                .andExpect(jsonPath("$.data[0].id").value(id));
    }

    @Test
    void unknownTypeListsTheSupportedOnes() throws Exception {
        mockMvc.perform(post("/api/v1/trash/note/1/restore").session(session))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(1002))
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.allOf(
                        org.hamcrest.Matchers.containsString("note"),
                        org.hamcrest.Matchers.containsString("inbox"))));
    }

    /**
     * 「恢复失败」有三种完全不同的原因，用户要做的事也完全不同：不存在（刷新一下）、
     * 没被删（点错了）、超期（别再找了）。合成一句「恢复失败」等于没说。
     */
    @Test
    void tellsApartMissingRecordAndRecordThatWasNeverDeleted() throws Exception {
        mockMvc.perform(post("/api/v1/trash/task/999999/restore").session(session))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value(1001))
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("回收站里没有这条记录")));

        long alive = insertAliveTask("没被删过的任务");
        mockMvc.perform(post("/api/v1/trash/task/{id}/restore", alive).session(session))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(1002))
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("没有被删除")));
    }

    /**
     * 回收站是用户唯一会看到「已删数据原始字段」的地方，而这些字段在库里都是英文标记。
     * 直接拼进摘要会得到「P1 · doing · 截止 2026-09-20」这种半中半英的东西，用户认不出是哪条。
     */
    @Test
    void detailTextIsHumanReadableInsteadOfRawEnums() throws Exception {
        String now = TimeUtil.now(TZ);
        jdbcTemplate.update("INSERT INTO task(title, priority, status, created_at, updated_at, deleted, deleted_at)"
                + " VALUES ('写周报', 'P1', 'doing', ?, ?, 1, ?)", now, now, now);
        jdbcTemplate.update("INSERT INTO schedule_event(title, event_type, event_date, created_at, updated_at, deleted, deleted_at)"
                + " VALUES ('深度写方案', 'deep_block', '2026-09-20', ?, ?, 1, ?)", now, now, now);
        jdbcTemplate.update("INSERT INTO inbox_item(raw_content, source, status, created_at, updated_at, deleted, deleted_at)"
                + " VALUES ('微信里冒出来的一句话', 'wecom', 'processed', ?, ?, 1, ?)", now, now, now);

        // 必须显式指定 UTF-8：MockHttpServletResponse.getContentAsString() 默认按 ISO-8859-1 解码，
        // 中文会变成「æ·±åº¦å」这种乱码，断言中文文案会假红。
        String body = mockMvc.perform(get("/api/v1/trash").session(session))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8);

        assertThat(body).contains("P1 · 进行中").contains("深度块").contains("来源 微信 · 待确认");
        assertThat(body).doesNotContain("doing").doesNotContain("deep_block").doesNotContain("processed");
    }

    /** 回收站能看到已删除的原始内容，必须和其它业务接口一样要求登录。 */
    @Test
    void requiresLogin() throws Exception {
        mockMvc.perform(get("/api/v1/trash"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value(2002));
        mockMvc.perform(post("/api/v1/trash/task/1/restore"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value(2002));
    }

    // ---------- helpers ----------

    private long insertDeletedTask(String title) {
        return insertDeletedTask(title, TimeUtil.now(TZ));
    }

    private long insertDeletedTask(String title, String deletedAt) {
        jdbcTemplate.update("INSERT INTO task(title, priority, status, created_at, updated_at, deleted, deleted_at)"
                + " VALUES (?, 'P2', 'todo', ?, ?, 1, ?)", title, TimeUtil.daysAgo(TZ, 40), deletedAt, deletedAt);
        return jdbcTemplate.queryForObject("SELECT id FROM task WHERE title=?", Long.class, title);
    }

    private long insertAliveTask(String title) {
        jdbcTemplate.update("INSERT INTO task(title, priority, status, created_at, updated_at, deleted)"
                + " VALUES (?, 'P2', 'todo', ?, ?, 0)", title, TimeUtil.now(TZ), TimeUtil.now(TZ));
        return jdbcTemplate.queryForObject("SELECT id FROM task WHERE title=?", Long.class, title);
    }

    private void insertDeletedEvent(String title) {
        String now = TimeUtil.now(TZ);
        jdbcTemplate.update("INSERT INTO schedule_event(title, event_type, event_date, created_at, updated_at, deleted, deleted_at)"
                + " VALUES (?, 'meeting', '2026-09-20', ?, ?, 1, ?)", title, now, now, now);
    }

    private void insertDeletedMemo(String title) {
        String now = TimeUtil.now(TZ);
        jdbcTemplate.update("INSERT INTO memo(title, content, grp, status, created_at, updated_at, deleted, deleted_at)"
                + " VALUES (?, '正文', 'life', 'active', ?, ?, 1, ?)", title, now, now, now);
    }

    private void insertDeletedKnowledge(String title) {
        String now = TimeUtil.now(TZ);
        jdbcTemplate.update("INSERT INTO knowledge_note(title, vault_path, sync_status, created_at, updated_at, deleted, deleted_at)"
                + " VALUES (?, '00-Inbox/a.md', 'synced', ?, ?, 1, ?)", title, now, now, now);
    }

    private void insertDeletedInbox(String raw) {
        String now = TimeUtil.now(TZ);
        jdbcTemplate.update("INSERT INTO inbox_item(raw_content, source, status, created_at, updated_at, deleted, deleted_at)"
                + " VALUES (?, 'web', 'archived', ?, ?, 1, ?)", raw, now, now, now);
    }
}
