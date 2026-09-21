package com.icecode.workbench.inbox;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
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
import org.springframework.test.web.servlet.MvcResult;

import com.icecode.workbench.auth.AuthConstants;

@SpringBootTest
@AutoConfigureMockMvc
class InboxFlowTest {

    private static final String TEST_ROOT = "target/test-workbench/inbox-" + UUID.randomUUID().toString();

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
        jdbcTemplate.update("DELETE FROM daily_plan_item");
        jdbcTemplate.update("DELETE FROM pomodoro");
        jdbcTemplate.update("DELETE FROM activity_log");
        jdbcTemplate.update("DELETE FROM favorite");
        jdbcTemplate.update("DELETE FROM schedule_event");
        jdbcTemplate.update("DELETE FROM task");
        jdbcTemplate.update("DELETE FROM inbox_item");
        jdbcTemplate.update("DELETE FROM ai_job");
        jdbcTemplate.update("UPDATE app_config SET config_value='true' WHERE config_key='app.initialized'");
        jdbcTemplate.update("UPDATE app_config SET config_value='Asia/Shanghai' WHERE config_key='app.timezone'");
        session = new MockHttpSession();
        session.setAttribute(AuthConstants.SESSION_USER, "tester");
    }

    @Test
    void completesCaptureClassifyConfirmAndTaskGeneration() throws Exception {
        long id = createInbox("明天下午3点约业务方对齐Q4需求评审");
        mockMvc.perform(post("/api/v1/inbox/classify").session(session))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.jobId").isNumber());
        assertThat(jdbcTemplate.queryForObject("SELECT status FROM inbox_item WHERE id=?", String.class, id)).isEqualTo("processed");
        assertThat(jdbcTemplate.queryForObject("SELECT ai_category FROM inbox_item WHERE id=?", String.class, id)).isEqualTo("schedule");
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM activity_log WHERE log_type='inbox'", Integer.class)).isEqualTo(1);

        mockMvc.perform(post("/api/v1/inbox/{id}/confirm", id).session(session)
                        .contentType("application/json")
                        .content("{\"category\":\"task\",\"title\":\"对齐Q4需求评审\",\"priority\":\"P0\",\"due\":\"2026-09-08\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.category").value("task"));
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM task WHERE source_inbox_id=?", Integer.class, id)).isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject("SELECT status FROM inbox_item WHERE id=?", String.class, id)).isEqualTo("archived");
    }

    /**
     * 低置信度（< 0.70）的语义是「不参与一键批量确认」，不是「必须改类别才能确认」。
     * 这条用例同时守住两件事：批量确认跳过它；用户手动确认（即便沿用 AI 的类别）必须成功。
     * 回归背景：规则整理器默认分支输出 task@0.58，曾经因为「沿用了 AI 类别就报 1002」，
     * 导致所有无关键字的条目都无法归档。
     */
    @Test
    void lowConfidenceSkipsBatchConfirmButAllowsManualConfirmWithSameCategory() throws Exception {
        long id = createInbox("随便看看");
        mockMvc.perform(post("/api/v1/inbox/classify").session(session)).andExpect(status().isOk());
        assertThat(jdbcTemplate.queryForObject("SELECT ai_confidence FROM inbox_item WHERE id=?", Double.class, id)).isLessThan(0.70);

        mockMvc.perform(post("/api/v1/inbox/confirm-high-confidence").session(session))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.length()").value(0));
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM task WHERE source_inbox_id=?", Integer.class, id)).isEqualTo(0);
        assertThat(jdbcTemplate.queryForObject("SELECT status FROM inbox_item WHERE id=?", String.class, id)).isEqualTo("processed");

        mockMvc.perform(post("/api/v1/inbox/{id}/confirm", id).session(session)
                        .contentType("application/json")
                        .content("{\"category\":\"task\",\"title\":\"随便看看\",\"priority\":\"P2\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.category").value("task"));
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM task WHERE source_inbox_id=?", Integer.class, id)).isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject("SELECT status FROM inbox_item WHERE id=?", String.class, id)).isEqualTo("archived");
    }

    /** 同一个「结束时间不晚于开始时间」约束，确认入口与 EventService 必须返回同一个错误码。 */
    @Test
    void rejectsScheduleConfirmWithEndNotAfterStart() throws Exception {
        long id = createInbox("明天下午3点约业务方对齐Q4需求评审");
        mockMvc.perform(post("/api/v1/inbox/classify").session(session)).andExpect(status().isOk());
        mockMvc.perform(post("/api/v1/inbox/{id}/confirm", id).session(session)
                        .contentType("application/json")
                        .content("{\"category\":\"schedule\",\"title\":\"对齐会\",\"due\":\"2026-09-08\","
                                + "\"start\":\"15:00\",\"end\":\"14:00\",\"eventType\":\"meeting\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(3003));
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM schedule_event WHERE source_inbox_id=?", Integer.class, id)).isEqualTo(0);
        assertThat(jdbcTemplate.queryForObject("SELECT status FROM inbox_item WHERE id=?", String.class, id)).isEqualTo("processed");
    }

    @Test
    void rejectsConfirmOnUnclassifiedItemWithActionableMessage() throws Exception {
        long id = createInbox("周五下午和技术经理过一下系统规划初稿");
        mockMvc.perform(post("/api/v1/inbox/{id}/confirm", id).session(session)
                        .contentType("application/json").content("{\"category\":\"task\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(1002))
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("待整理")));
    }

    @Test
    void allowsManualCategoryChangeFromTaskToSchedule() throws Exception {
        long id = createInbox("完成服务器续费");
        mockMvc.perform(post("/api/v1/inbox/classify").session(session)).andExpect(status().isOk());
        mockMvc.perform(post("/api/v1/inbox/{id}/confirm", id).session(session)
                        .contentType("application/json")
                        .content("{\"category\":\"schedule\",\"title\":\"服务器续费窗口\",\"due\":\"2026-09-08\",\"start\":\"15:00\",\"end\":\"16:00\",\"eventType\":\"other\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.category").value("schedule"));
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM schedule_event WHERE source_inbox_id=?", Integer.class, id)).isEqualTo(1);
    }

    /**
     * US-4.2 主链路：整理命中「会议/对齐」但抽不出日期时，确认生成必须能成功，
     * 并且日程落到「待定时间」区（event_date 为 NULL），而不是弹一句「必须填写日期」把用户堵住。
     */
    @Test
    void confirmsScheduleWithoutDateIntoPendingArea() throws Exception {
        long id = createInbox("和业务方对齐一下Q4复盘的口径");
        mockMvc.perform(post("/api/v1/inbox/classify").session(session)).andExpect(status().isOk());
        assertThat(jdbcTemplate.queryForObject("SELECT ai_category FROM inbox_item WHERE id=?", String.class, id))
                .isEqualTo("schedule");

        mockMvc.perform(post("/api/v1/inbox/{id}/confirm", id).session(session)
                        .contentType("application/json")
                        .content("{\"category\":\"schedule\",\"title\":\"和业务方对齐复盘口径\",\"eventType\":\"meeting\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.category").value("schedule"));

        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM schedule_event WHERE source_inbox_id=? AND event_date IS NULL",
                Integer.class, id)).isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject("SELECT status FROM inbox_item WHERE id=?", String.class, id))
                .isEqualTo("archived");
    }

    /**
     * 整理页的「开始时间」是自由文本框，用户很容易输成 9:00。
     * 这时返回的提示必须写清期望格式，绝不能把正则 ^$|([01]\d|2[0-3]):[0-5]\d 甩给用户。
     */
    @Test
    void malformedStartTimeHintTellsExpectedFormatInsteadOfRawRegex() throws Exception {
        long id = createInbox("明天下午和业务方对齐Q4复盘口径");
        mockMvc.perform(post("/api/v1/inbox/classify").session(session)).andExpect(status().isOk());
        mockMvc.perform(post("/api/v1/inbox/{id}/confirm", id).session(session)
                        .contentType("application/json")
                        .content("{\"category\":\"schedule\",\"title\":\"对齐会\",\"due\":\"2026-09-08\","
                                + "\"start\":\"9:00\",\"eventType\":\"meeting\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(1002))
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("HH:mm")))
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.not(
                        org.hamcrest.Matchers.containsString("^$|"))));
    }

    @Test
    void repeatedConfirmIsIdempotent() throws Exception {
        long id = createInbox("记得给小李的方案写评审意见");
        mockMvc.perform(post("/api/v1/inbox/classify").session(session)).andExpect(status().isOk());
        mockMvc.perform(post("/api/v1/inbox/{id}/confirm", id).session(session)
                        .contentType("application/json").content("{\"category\":\"favorite\"}"))
                .andExpect(status().isOk());
        mockMvc.perform(post("/api/v1/inbox/{id}/confirm", id).session(session)
                        .contentType("application/json").content("{\"category\":\"favorite\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.alreadyConfirmed").value(true));
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM favorite WHERE source_inbox_id=?", Integer.class, id)).isEqualTo(1);
    }

    @Test
    void failedClassificationKeepsRawAndCanRetry() throws Exception {
        long id = createInbox("周五下午和技术经理过一下系统规划初稿");
        jdbcTemplate.update("UPDATE inbox_item SET status='failed' WHERE id=?", id);
        mockMvc.perform(post("/api/v1/inbox/{id}/reclassify", id).session(session))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.status").value("processed"));
        assertThat(jdbcTemplate.queryForObject("SELECT raw_content FROM inbox_item WHERE id=?", String.class, id)).contains("系统规划");
    }

    @Test
    void returnsJobStatusAndProcessedItems() throws Exception {
        createInbox("明天下午3点约业务方对齐Q4需求评审");
        MvcResult result = mockMvc.perform(post("/api/v1/inbox/classify").session(session))
                .andExpect(status().isOk()).andReturn();
        String body = result.getResponse().getContentAsString();
        String marker = "\"jobId\":";
        long jobId = Long.parseLong(body.substring(body.indexOf(marker) + marker.length(), body.indexOf("}", body.indexOf(marker))));
        mockMvc.perform(get("/api/v1/inbox/jobs/{id}", jobId).session(session))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.job.status").value("success"))
                .andExpect(jsonPath("$.data.processed.length()").value(1));
    }

    @Test
    void highConfidenceBatchConfirmSkipsLowConfidence() throws Exception {
        createInbox("明天下午3点约业务方对齐Q4需求评审");
        createInbox("随便看看");
        mockMvc.perform(post("/api/v1/inbox/classify").session(session)).andExpect(status().isOk());
        mockMvc.perform(post("/api/v1/inbox/confirm-high-confidence").session(session))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.length()").value(1));
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM schedule_event", Integer.class)).isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM inbox_item WHERE status='processed'", Integer.class)).isEqualTo(1);
    }

    /**
     * 「收录整理的数据默认选择今天」：一键批量确认时用户看不到任何字段，
     * 整理器又抽不出日期，这时必须按今天生成。
     *
     * <p>回归背景：这批条目若没有日期，既不计入驾驶舱的今日清单，也不在任何按日视图里露出，
     * 用户看到的就是「点了生成，但哪儿都找不到」——所以缺省值只能是今天。</p>
     */
    @Test
    void highConfidenceBatchConfirmDefaultsMissingDateToToday() throws Exception {
        createInbox("完成服务器续费");
        mockMvc.perform(post("/api/v1/inbox/classify").session(session)).andExpect(status().isOk());
        mockMvc.perform(post("/api/v1/inbox/confirm-high-confidence").session(session))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.length()").value(1));
        String today = com.icecode.workbench.util.TimeUtil.format(
                com.icecode.workbench.util.TimeUtil.localDate("Asia/Shanghai"));
        assertThat(jdbcTemplate.queryForObject("SELECT due_date FROM task", String.class)).isEqualTo(today);
    }

    /**
     * 与上一条相对：**手动**确认时日期留空仍然表示「时间还没定下来」，后端不能替用户填今天。
     * 默认值放在前端预填（用户可以改、也可以清空），后端隐式兜底会让「清空 = 待定」没有入口。
     */
    @Test
    void manualConfirmKeepsBlankDateAsNoDate() throws Exception {
        long id = createInbox("完成服务器续费");
        mockMvc.perform(post("/api/v1/inbox/classify").session(session)).andExpect(status().isOk());
        mockMvc.perform(post("/api/v1/inbox/{id}/confirm", id).session(session)
                        .contentType("application/json")
                        .content("{\"category\":\"task\",\"title\":\"服务器续费\",\"priority\":\"P2\"}"))
                .andExpect(status().isOk());
        assertThat(jdbcTemplate.queryForObject("SELECT due_date FROM task WHERE source_inbox_id=?", String.class, id)).isNull();
    }

    @Test
    void softDeleteHidesInboxWithoutLosingRecord() throws Exception {
        long id = createInbox("充电桩电费发票报销");
        mockMvc.perform(delete("/api/v1/inbox/{id}", id).session(session)).andExpect(status().isOk());
        mockMvc.perform(get("/api/v1/inbox").session(session))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.length()").value(0));
        assertThat(jdbcTemplate.queryForObject("SELECT deleted FROM inbox_item WHERE id=?", Integer.class, id)).isEqualTo(1);
    }

    /**
     * 「每周三下午3点开周会」这类周期安排：整理器要把它认成**重复**日程（连续 4 周），
     * 确认后一次性生成 4 条独立记录。
     *
     * <p>注意与「周三开会」的区别 —— 只差一个「每」字，但那一个字的差别决定了
     * 界面上是出现一次还是出现四次。所以这条用例同时钉住**没有「每」时不能误判成重复**。</p>
     */
    @Test
    void weeklyWordingProducesFourOccurrencesWhilePlainWeekdayStaysSingle() throws Exception {
        long weekly = createInbox("每周三下午3点开周会");
        mockMvc.perform(post("/api/v1/inbox/classify").session(session)).andExpect(status().isOk());
        String payload = jdbcTemplate.queryForObject("SELECT ai_payload FROM inbox_item WHERE id=?", String.class, weekly);
        assertThat(payload).contains("\"repeatWeeks\":4");
        assertThat(jdbcTemplate.queryForObject("SELECT ai_category FROM inbox_item WHERE id=?", String.class, weekly)).isEqualTo("schedule");

        mockMvc.perform(post("/api/v1/inbox/{id}/confirm", weekly).session(session)
                        .contentType("application/json")
                        .content("{\"category\":\"schedule\",\"title\":\"周会\",\"due\":\"2026-09-16\","
                                + "\"start\":\"15:00\",\"end\":\"16:00\",\"eventType\":\"meeting\",\"repeatWeeks\":4}"))
                .andExpect(status().isOk());
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM schedule_event WHERE source_inbox_id=?", Integer.class, weekly)).isEqualTo(4);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM schedule_event WHERE source_inbox_id=? AND repeat_total=4 AND repeat_group IS NOT NULL",
                Integer.class, weekly)).isEqualTo(4);
        // 四期日期依次 +7 天
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM schedule_event WHERE source_inbox_id=? AND event_date IN ('2026-09-16','2026-09-23','2026-09-30','2026-10-07')",
                Integer.class, weekly)).isEqualTo(4);

        // 说「周三」而不说「每周三」——不该被认成重复
        long once = createInbox("周三下午3点开周会");
        mockMvc.perform(post("/api/v1/inbox/classify").session(session)).andExpect(status().isOk());
        String oncePayload = jdbcTemplate.queryForObject("SELECT ai_payload FROM inbox_item WHERE id=?", String.class, once);
        assertThat(oncePayload).doesNotContain("\"repeatWeeks\":4");
    }

    /**
     * 待定日程不参与重复：整理时没抽出日期，就无从谈「每周的同一天」。
     * 确认卡上即使带着 repeatWeeks，也只写一条（不能替用户编一个日期出来）。
     */
    @Test
    void undatedScheduleIsNeverRepeated() throws Exception {
        long id = createInbox("每周三开周会但没说哪天开始");
        mockMvc.perform(post("/api/v1/inbox/classify").session(session)).andExpect(status().isOk());
        mockMvc.perform(post("/api/v1/inbox/{id}/confirm", id).session(session)
                        .contentType("application/json")
                        .content("{\"category\":\"schedule\",\"title\":\"周会\",\"eventType\":\"meeting\",\"repeatWeeks\":4}"))
                .andExpect(status().isOk());
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM schedule_event WHERE source_inbox_id=?", Integer.class, id)).isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM schedule_event WHERE source_inbox_id=? AND event_date IS NULL", Integer.class, id)).isEqualTo(1);
    }

    // ------------------------------------------------------------ 图片条目（阶段二）

    /**
     * 图片条目**永远不参与**一键批量确认，哪怕置信度是 0.95。
     *
     * <p>这条用例守的是用户最初的那句抱怨：「传张邮件截图，还没看到解析结果，
     * 任务就被建好了」。截图是整张图，里面往往同时写着日期、负责人、几件事 ——
     * 视觉模型给 0.9 的置信度也不代表它读对了，用户必须**看过**才能落库。
     * 所以判定条件是 {@code origin='image'}，与置信度无关。</p>
     */
    @Test
    void imageItemsNeverJoinHighConfidenceBatchConfirmEvenWhenConfident() throws Exception {
        long id = insertImageItem("会议通知：周三 14:00 需求评审", 0.95);

        mockMvc.perform(post("/api/v1/inbox/confirm-high-confidence").session(session))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.length()").value(0));

        assertThat(jdbcTemplate.queryForObject("SELECT status FROM inbox_item WHERE id=?", String.class, id))
                .isEqualTo("processed");
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM schedule_event", Integer.class)).isEqualTo(0);
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM task", Integer.class)).isEqualTo(0);
    }

    /** 图片条目虽然不进批量确认，但**人工逐条确认必须照常可用**（低置信度也不拦）。 */
    @Test
    void imageItemStillAllowsManualConfirm() throws Exception {
        long id = insertImageItem("会议通知：周三 14:00 需求评审", 0.55);

        mockMvc.perform(post("/api/v1/inbox/{id}/confirm", id).session(session)
                        .contentType("application/json")
                        .content("{\"category\":\"schedule\",\"title\":\"需求评审\",\"eventType\":\"meeting\",\"due\":\"2026-09-23\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.category").value("schedule"));
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM schedule_event WHERE source_inbox_id=?", Integer.class, id)).isEqualTo(1);
    }

    /**
     * 纯文本的条目不受影响 —— 排除规则必须**只**盯 {@code origin='image'}，
     * 不能顺手把整条链路关掉。
     */
    @Test
    void textItemsStillJoinHighConfidenceBatchConfirm() throws Exception {
        createInbox("明天下午3点约业务方对齐Q4需求评审");
        mockMvc.perform(post("/api/v1/inbox/classify").session(session)).andExpect(status().isOk());

        mockMvc.perform(post("/api/v1/inbox/confirm-high-confidence").session(session))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.length()").value(1));
    }

    /**
     * 批量确认是**一个事务**：其中一条不符合校验，整体回滚，不留半成品。
     *
     * <p>用 {@code GET} 数条数验证，而不是看响应 —— 只看响应的话，「部分成功」
     * 和「全失败」都是 4xx，看不出区别。</p>
     */
    @Test
    void batchConfirmRollsBackEverythingWhenOneItemIsInvalid() throws Exception {
        long okId = createInbox("开会");
        mockMvc.perform(post("/api/v1/inbox/classify").session(session)).andExpect(status().isOk());

        String body = "{\"items\":["
                + "{\"inboxId\":" + okId + ",\"confirm\":{\"category\":\"task\",\"title\":\"开会\",\"priority\":\"P2\"}},"
                // 第二条的标题超出 @Size(max=200)：整批必须回滚
                + "{\"inboxId\":" + okId + ",\"confirm\":{\"category\":\"task\",\"title\":\""
                + repeat('长', 201) + "\",\"priority\":\"P2\"}}"
                + "]}";
        mockMvc.perform(post("/api/v1/inbox/confirm-items").session(session)
                        .contentType("application/json").content(body))
                .andExpect(status().isBadRequest());

        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM task", Integer.class)).isEqualTo(0);
        assertThat(jdbcTemplate.queryForObject("SELECT status FROM inbox_item WHERE id=?", String.class, okId))
                .isEqualTo("processed");
    }

    @Test
    void batchConfirmReturnsOneEntityPerItem() throws Exception {
        long first = createInbox("开会");
        long second = createInbox("买东西");
        mockMvc.perform(post("/api/v1/inbox/classify").session(session)).andExpect(status().isOk());

        String body = "{\"items\":["
                + "{\"inboxId\":" + first + ",\"confirm\":{\"category\":\"task\",\"title\":\"开会\",\"priority\":\"P2\"}},"
                + "{\"inboxId\":" + second + ",\"confirm\":{\"category\":\"favorite\",\"title\":\"买东西\"}}"
                + "]}";
        mockMvc.perform(post("/api/v1/inbox/confirm-items").session(session)
                        .contentType("application/json").content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(2));

        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM task", Integer.class)).isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM favorite", Integer.class)).isEqualTo(1);
    }

    /** 超过 50 条要报 1002 并说明上限 —— 一次几百条的确认会把池里的 4 个连接全占死。 */
    @Test
    void batchConfirmRejectsOverlongList() throws Exception {
        StringBuilder body = new StringBuilder("{\"items\":[");
        for (int i = 0; i < 51; i++) {
            if (i > 0) {
                body.append(',');
            }
            body.append("{\"inboxId\":1,\"confirm\":{\"category\":\"task\",\"title\":\"x\",\"priority\":\"P2\"}}");
        }
        body.append("]}");
        mockMvc.perform(post("/api/v1/inbox/confirm-items").session(session)
                        .contentType("application/json").content(body.toString()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(1002));
    }

    /** 解析进度要能查到「有几条在跑、几条失败」，否则前端只能靠猜。 */
    @Test
    void parseStatusReportsRunningFailedAndSuccessCounts() throws Exception {
        insertImageItem("解析中的图", 0.0);
        jdbcTemplate.update("UPDATE inbox_item SET parse_status='running' WHERE raw_content='解析中的图'");
        insertImageItem("失败的图", 0.0);
        jdbcTemplate.update("UPDATE inbox_item SET parse_status='failed', parse_error='这张图里没读出任何内容'"
                + " WHERE raw_content='失败的图'");
        insertImageItem("成功的图", 0.0);
        jdbcTemplate.update("UPDATE inbox_item SET parse_status='success' WHERE raw_content='成功的图'");

        mockMvc.perform(get("/api/v1/inbox/parse-status").session(session))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.running").value(1))
                .andExpect(jsonPath("$.data.failed").value(1))
                .andExpect(jsonPath("$.data.success").value(1))
                .andExpect(jsonPath("$.data.lastError").value("这张图里没读出任何内容"));
    }

    /** 未配置视觉模型时触发解析：同步路径就要报错并指向设置页，不能静默 202。 */
    @Test
    void parseImagesWithoutVisionModelIsRejectedSynchronously() throws Exception {
        mockMvc.perform(post("/api/v1/inbox/parse-images").session(session)
                        .contentType("application/json")
                        .content("{\"attachmentIds\":[1],\"source\":\"web\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(3015));
    }

    @Test
    void parseImagesRejectsEmptyAttachmentList() throws Exception {
        mockMvc.perform(post("/api/v1/inbox/parse-images").session(session)
                        .contentType("application/json")
                        .content("{\"attachmentIds\":[],\"source\":\"web\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(1002));
    }

    /** 直接插一条伪装成「视觉模型刚写完」的图片条目，绕过解析（不能真调模型）。 */
    private long insertImageItem(String raw, double confidence) {
        jdbcTemplate.update("INSERT INTO inbox_item(raw_content, content_type, source, status, origin,"
                        + " ai_category, ai_confidence, parse_status, processed_at, created_at, updated_at, is_demo)"
                        + " VALUES (?,'text','web','processed','image','schedule',?,'success',"
                        + " '2026-09-16 10:00:00','2026-09-16 10:00:00','2026-09-16 10:00:00',0)",
                raw, Double.valueOf(confidence));
        return jdbcTemplate.queryForObject(
                "SELECT id FROM inbox_item WHERE raw_content=?", Long.class, raw).longValue();
    }

    private static String repeat(char ch, int times) {
        StringBuilder builder = new StringBuilder();
        for (int i = 0; i < times; i++) {
            builder.append(ch);
        }
        return builder.toString();
    }

    private long createInbox(String raw) throws Exception {
        mockMvc.perform(post("/api/v1/inbox").session(session)
                        .contentType("application/json")
                        .content("{\"raw\":\"" + raw + "\"}"))
                .andExpect(status().isOk());
        return jdbcTemplate.queryForObject("SELECT id FROM inbox_item WHERE raw_content=?", Long.class, raw).longValue();
    }
}
