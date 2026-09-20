package com.icecode.workbench.transfer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasItem;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.LocalDate;
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
 * 「移至」的端到端约束。
 *
 * <p>这个功能的实质是「目标表新建 + 源记录软删 + 一条流水」，三件事在同一个事务里完成，
 * 所以每条用例除了断言目标记录，还要断言**源记录确实离开了原菜单**（而不是两个菜单里都还在），
 * 以及**它能在回收站里找到**（否则用户会以为数据被吞了）。</p>
 *
 * <p>{@code warnings} 的断言用 {@code hasItem(containsString(...))} 而不是下标：警告条目的
 * 顺序不是契约，条数与内容才是 —— 按下标写会在有人调整文案顺序时假红。</p>
 */
@SpringBootTest
@AutoConfigureMockMvc
class TransferFlowTest {

    private static final String TEST_ROOT = "target/test-workbench/transfer-" + UUID.randomUUID().toString();

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("workbench.data-dir", () -> TEST_ROOT + "/data");
        registry.add("workbench.home-dir", () -> TEST_ROOT);
    }

    @Autowired private MockMvc mockMvc;
    @Autowired private JdbcTemplate jdbcTemplate;
    private MockHttpSession session;

    private String tomorrow() { return TimeUtil.format(LocalDate.now().plusDays(1)); }

    @BeforeEach
    void setUp() {
        jdbcTemplate.update("DELETE FROM daily_plan_item");
        jdbcTemplate.update("DELETE FROM pomodoro");
        jdbcTemplate.update("DELETE FROM activity_log");
        // 附件先清：它挂在小表上，留着会让「移动是否把附件带走了」这类断言读到上一条用例的残留
        jdbcTemplate.update("DELETE FROM attachment");
        jdbcTemplate.update("DELETE FROM task");
        jdbcTemplate.update("DELETE FROM schedule_event");
        jdbcTemplate.update("DELETE FROM memo");
        jdbcTemplate.update("UPDATE app_config SET config_value='true' WHERE config_key='app.initialized'");
        jdbcTemplate.update("UPDATE app_config SET config_value='Asia/Shanghai' WHERE config_key='app.timezone'");
        session = new MockHttpSession();
        session.setAttribute(AuthConstants.SESSION_USER, "tester");
    }

    private void createTask(String title, String priority, String due, String note, String description) throws Exception {
        StringBuilder body = new StringBuilder("{\"title\":\"" + title + "\",\"priority\":\"" + priority + "\"");
        if (due != null) body.append(",\"due\":\"").append(due).append("\"");
        if (note != null) body.append(",\"note\":\"").append(note).append("\"");
        if (description != null) body.append(",\"description\":\"").append(description).append("\"");
        body.append("}");
        mockMvc.perform(post("/api/v1/tasks").session(session)
                        .contentType("application/json").content(body.toString()))
                .andExpect(status().isOk());
    }

    private void createEvent(String title, String type, String date) throws Exception {
        mockMvc.perform(post("/api/v1/events").session(session)
                        .contentType("application/json")
                        .content("{\"title\":\"" + title + "\",\"type\":\"" + type + "\",\"date\":\""
                                + (date == null ? "" : date) + "\"}"))
                .andExpect(status().isOk());
    }

    private void createMemo(String title, String content) throws Exception {
        mockMvc.perform(post("/api/v1/memos").session(session)
                        .contentType("application/json")
                        .content("{\"title\":\"" + title + "\",\"content\":\"" + content + "\"}"))
                .andExpect(status().isOk());
    }

    private long idOf(String table, String title) {
        return jdbcTemplate.queryForObject("SELECT id FROM " + table + " WHERE title=?", Long.class, title).longValue();
    }

    private int flag(String sql, Object... args) {
        Integer value = jdbcTemplate.queryForObject(sql, Integer.class, args);
        return value == null ? 0 : value.intValue();
    }

    private int activityCount(String like) {
        return flag("SELECT COUNT(*) FROM activity_log WHERE content LIKE ?", "%" + like + "%");
    }

    @Test
    void movesTaskToEventCarryingTheDueDateAndReportsLostFields() throws Exception {
        createTask("写季度复盘", "P1", tomorrow(), "周五前给组长", "含 Q3 数据");
        long id = idOf("task", "写季度复盘");

        mockMvc.perform(post("/api/v1/transfers").session(session)
                        .contentType("application/json")
                        .content("{\"fromType\":\"task\",\"toType\":\"event\",\"id\":" + id + "}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.fromLabel").value("任务"))
                .andExpect(jsonPath("$.data.toLabel").value("日程"))
                .andExpect(jsonPath("$.data.title").value("写季度复盘"))
                // 任务的描述/备注/优先级在日程里没有列，必须逐项说清，不能静默丢掉
                .andExpect(jsonPath("$.data.warnings.length()").value(1))
                .andExpect(jsonPath("$.data.warnings", hasItem(containsString("任务描述"))))
                .andExpect(jsonPath("$.data.warnings", hasItem(containsString("备注"))))
                .andExpect(jsonPath("$.data.warnings", hasItem(containsString("P1"))));

        // 落在正确的日期上（而不是默认今天或待定区）
        mockMvc.perform(get("/api/v1/events").param("date", tomorrow()).session(session))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(1))
                .andExpect(jsonPath("$.data[0].title").value("写季度复盘"));

        // 原菜单里不再出现
        mockMvc.perform(get("/api/v1/tasks").session(session))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.length()").value(0));

        // 源记录不是人间蒸发：它在回收站里，30 天内可恢复
        mockMvc.perform(get("/api/v1/trash").session(session))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].type").value("task"))
                .andExpect(jsonPath("$.data[0].title").value("写季度复盘"));
        assertThat(flag("SELECT deleted FROM task WHERE id=?", id)).isEqualTo(1);
        assertThat(flag("SELECT deleted_at IS NOT NULL FROM task WHERE id=?", id)).isEqualTo(1);

        // 时间线要留下「移至」这一步，否则用户回头查不到这条记录去哪儿了
        assertThat(activityCount("从「任务」移至「日程」")).isEqualTo(1);
    }

    @Test
    void movesTaskWithoutDueDateIntoThePendingAreaAndSaysSo() throws Exception {
        createTask("调研竞品", "P2", null, null, null);
        long id = idOf("task", "调研竞品");

        mockMvc.perform(post("/api/v1/transfers").session(session)
                        .contentType("application/json")
                        .content("{\"fromType\":\"task\",\"toType\":\"event\",\"id\":" + id + "}"))
                .andExpect(status().isOk())
                // 没有截止日期 = 没有日期，这里不能偷偷补今天（US-4.2 的「待定时间」是真实状态）
                .andExpect(jsonPath("$.data.warnings.length()").value(1))
                .andExpect(jsonPath("$.data.warnings", hasItem(containsString("待定时间"))));

        mockMvc.perform(get("/api/v1/events/pending").session(session))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(1))
                .andExpect(jsonPath("$.data[0].title").value("调研竞品"));
        assertThat(flag("SELECT event_date IS NULL FROM schedule_event WHERE title='调研竞品' AND deleted=0")).isEqualTo(1);
    }

    @Test
    void movesTaskToMemoKeepingDescriptionAndNoteAsBody() throws Exception {
        createTask("整理复盘方案", "P2", tomorrow(), "周五前给组长", "复盘方案与 Q3 数据");
        long id = idOf("task", "整理复盘方案");

        mockMvc.perform(post("/api/v1/transfers").session(session)
                        .contentType("application/json")
                        .content("{\"fromType\":\"task\",\"toType\":\"memo\",\"id\":" + id + "}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.toLabel").value("备忘"))
                // 截止日期与优先级在备忘里没有列，有值就必须说出来
                .andExpect(jsonPath("$.data.warnings.length()").value(1))
                .andExpect(jsonPath("$.data.warnings", hasItem(containsString("截止日期"))));

        String content = jdbcTemplate.queryForObject(
                "SELECT content FROM memo WHERE title='整理复盘方案' AND deleted=0", String.class);
        assertThat(content).contains("复盘方案与 Q3 数据").contains("周五前给组长");
        // 分组沿用备忘自己的规则（内容里有「方案」→ 工作），不是另写一套
        assertThat(jdbcTemplate.queryForObject(
                "SELECT grp FROM memo WHERE title='整理复盘方案' AND deleted=0", String.class)).isEqualTo("work");
        assertThat(flag("SELECT deleted FROM task WHERE id=?", id)).isEqualTo(1);
    }

    @Test
    void movesPendingEventToTaskWithoutInventingADueDate() throws Exception {
        createEvent("和业务对齐", "meeting", null);
        long id = idOf("schedule_event", "和业务对齐");

        mockMvc.perform(post("/api/v1/transfers").session(session)
                        .contentType("application/json")
                        .content("{\"fromType\":\"event\",\"toType\":\"task\",\"id\":" + id + "}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.toLabel").value("任务"))
                .andExpect(jsonPath("$.data.warnings.length()").value(2))
                .andExpect(jsonPath("$.data.warnings", hasItem(containsString("日程类型"))))
                .andExpect(jsonPath("$.data.warnings", hasItem(containsString("时间待定"))));

        mockMvc.perform(get("/api/v1/tasks").session(session))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(1))
                .andExpect(jsonPath("$.data[0].title").value("和业务对齐"));
        assertThat(flag("SELECT due_date IS NULL FROM task WHERE title='和业务对齐' AND deleted=0")).isEqualTo(1);
        assertThat(flag("SELECT deleted FROM schedule_event WHERE id=?", id)).isEqualTo(1);
    }

    @Test
    void movesMemoToEventIntoPendingAreaAndWarnsThatTheBodyCannotCome() throws Exception {
        createMemo("班车时刻表", "7:20 小区门口，18:10 返程");
        long id = idOf("memo", "班车时刻表");

        mockMvc.perform(post("/api/v1/transfers").session(session)
                        .contentType("application/json")
                        .content("{\"fromType\":\"memo\",\"toType\":\"event\",\"id\":" + id + "}"))
                .andExpect(status().isOk())
                // 日程只有标题一个文本字段，备忘的正文必然留不下 —— 要明说，并指出更好的去处
                .andExpect(jsonPath("$.data.warnings", hasItem(containsString("正文"))))
                .andExpect(jsonPath("$.data.warnings", hasItem(containsString("任务"))))
                .andExpect(jsonPath("$.data.warnings", hasItem(containsString("待定时间"))));

        mockMvc.perform(get("/api/v1/events/pending").session(session))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(1))
                .andExpect(jsonPath("$.data[0].title").value("班车时刻表"));
        assertThat(flag("SELECT deleted FROM memo WHERE id=?", id)).isEqualTo(1);
    }

    @Test
    void movesMemoToTaskAndSaysWhenTheBodyIsTruncated() throws Exception {
        StringBuilder body = new StringBuilder();
        for (int index = 0; index < 2500; index++) body.append("字");
        createMemo("长备忘", body.toString());
        long id = idOf("memo", "长备忘");

        mockMvc.perform(post("/api/v1/transfers").session(session)
                        .contentType("application/json")
                        .content("{\"fromType\":\"memo\",\"toType\":\"task\",\"id\":" + id + "}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.warnings", hasItem(containsString("2500 字"))))
                .andExpect(jsonPath("$.data.warnings", hasItem(containsString("2000 字"))));

        // 截断而不是报错：任务描述上限 2000，超出部分不保留，但这条记录仍然移过去了
        assertThat(flag("SELECT LENGTH(description) FROM task WHERE title='长备忘' AND deleted=0")).isEqualTo(2000);
        assertThat(flag("SELECT deleted FROM memo WHERE id=?", id)).isEqualTo(1);
    }

    @Test
    void rejectsSameMenuAndUnknownMenuType() throws Exception {
        createTask("写周报", "P2", null, null, null);
        long id = idOf("task", "写周报");

        mockMvc.perform(post("/api/v1/transfers").session(session)
                        .contentType("application/json")
                        .content("{\"fromType\":\"task\",\"toType\":\"task\",\"id\":" + id + "}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(1002))
                .andExpect(jsonPath("$.message").value(containsString("已经在「任务」里了")));

        mockMvc.perform(post("/api/v1/transfers").session(session)
                        .contentType("application/json")
                        .content("{\"fromType\":\"note\",\"toType\":\"task\",\"id\":" + id + "}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(1002))
                .andExpect(jsonPath("$.message").value(containsString("任务(task) / 日程(event) / 备忘(memo)")));

        // 被拒绝时不能留下半成品：源记录必须还在原菜单里
        assertThat(flag("SELECT deleted FROM task WHERE id=?", id)).isEqualTo(0);
    }

    @Test
    void returnsNotFoundWhenTheSourceRecordIsGone() throws Exception {
        mockMvc.perform(post("/api/v1/transfers").session(session)
                        .contentType("application/json")
                        .content("{\"fromType\":\"task\",\"toType\":\"memo\",\"id\":99999}"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value(1001));

        // 已经在回收站里的记录不能再移一次
        createTask("已删任务", "P2", null, null, null);
        long id = idOf("task", "已删任务");
        jdbcTemplate.update("UPDATE task SET deleted=1, deleted_at='2026-09-12 10:00:00' WHERE id=?", id);
        mockMvc.perform(post("/api/v1/transfers").session(session)
                        .contentType("application/json")
                        .content("{\"fromType\":\"task\",\"toType\":\"memo\",\"id\":" + id + "}"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value(1001));
    }

    @Test
    void keepsDemoFlagSoClearingSampleDataStillRemovesIt() throws Exception {
        createTask("示例任务", "P2", tomorrow(), null, null);
        long id = idOf("task", "示例任务");
        jdbcTemplate.update("UPDATE task SET is_demo=1 WHERE id=?", id);

        mockMvc.perform(post("/api/v1/transfers").session(session)
                        .contentType("application/json")
                        .content("{\"fromType\":\"task\",\"toType\":\"event\",\"id\":" + id + "}"))
                .andExpect(status().isOk());

        // 示例标记必须跟着走：否则「清空示例数据」清不掉它，用户会看到「已清空」却还剩一条
        assertThat(flag("SELECT is_demo FROM schedule_event WHERE title='示例任务' AND deleted=0")).isEqualTo(1);
    }

    /**
     * 附件要跟着记录一起搬（2026-09-19 补）。
     *
     * <p>不搬的话附件留在源记录名下，而源记录马上进回收站 —— 结果是**两边都看不到**：
     * 新记录上没有，回收站里那条也不显示附件。这类静默失效最难查：移动动作本身完全成功，
     * 接口返回 200、没有任何报错，用户只是过几天发现附件不见了。</p>
     */
    @Test
    void movesAttachmentsTogetherWithTheRecord() throws Exception {
        createMemo("带附件的备忘", "正文");
        long memoId = idOf("memo", "带附件的备忘");
        long attachmentId = insertAttachment("memo", memoId);

        mockMvc.perform(post("/api/v1/transfers").session(session)
                        .contentType("application/json")
                        .content("{\"fromType\":\"memo\",\"toType\":\"task\",\"id\":" + memoId + "}"))
                .andExpect(status().isOk())
                // 搬了几条也要如实回报：界面上那句话是用户唯一能确认「附件没丢」的地方
                .andExpect(jsonPath("$.data.movedAttachments").value(1));

        long newTaskId = idOf("task", "带附件的备忘");
        assertThat(jdbcTemplate.queryForObject(
                "SELECT owner_type FROM attachment WHERE id=?", String.class, Long.valueOf(attachmentId)))
                .isEqualTo("task");
        assertThat(jdbcTemplate.queryForObject(
                "SELECT owner_id FROM attachment WHERE id=?", Long.class, Long.valueOf(attachmentId)))
                .isEqualTo(newTaskId);

        // 源记录已经在回收站里，它名下不该再留着这个附件（attachment 的 deleted 也没被连带软删 ——
        // 它是「搬走了」，不是「删掉了」）
        mockMvc.perform(get("/api/v1/attachments").param("ownerType", "memo")
                        .param("ownerId", String.valueOf(memoId)).session(session))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(0));
        assertThat(flag("SELECT deleted FROM attachment WHERE id=?", Long.valueOf(attachmentId))).isZero();
    }

    /** 没有附件的记录移动后不该凭空多出附件，movedAttachments 也应当是 0（而不是漏字段）。 */
    @Test
    void movingARecordWithoutAttachmentsReportsZero() throws Exception {
        createMemo("光杆备忘", "正文");
        long memoId = idOf("memo", "光杆备忘");

        mockMvc.perform(post("/api/v1/transfers").session(session)
                        .contentType("application/json")
                        .content("{\"fromType\":\"memo\",\"toType\":\"task\",\"id\":" + memoId + "}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.movedAttachments").value(0));
    }

    /**
     * 直接插一行附件。
     *
     * <p>本用例验的是**转绑**，不需要真的走一遍上传 —— 落盘、魔数、大小那些校验已经由
     * {@code AttachmentFlowTest} 覆盖，这里再跑一遍只是让这条用例变慢变脆。</p>
     */
    private long insertAttachment(String ownerType, long ownerId) {
        jdbcTemplate.update("INSERT INTO attachment(owner_type, owner_id, file_name, original_name,"
                        + " mime_type, byte_size, sha256, sort_order, created_at, updated_at, deleted, is_demo)"
                        + " VALUES (?,?,?,?,?,?,?,0,?,?,0,0)",
                ownerType, Long.valueOf(ownerId), "abcdef0123456789.png", "费用截图.png",
                "image/png", Long.valueOf(1234L), "abcdef0123456789abcdef",
                "2026-09-19 12:00:00", "2026-09-19 12:00:00");
        return jdbcTemplate.queryForObject("SELECT id FROM attachment WHERE file_name=?",
                Long.class, "abcdef0123456789.png").longValue();
    }
}
