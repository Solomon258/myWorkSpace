package com.icecode.workbench.schedule;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;
import java.util.Map;
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
class EventFlowTest {

    private static final String TEST_ROOT = "target/test-workbench/event-" + UUID.randomUUID().toString();

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("workbench.data-dir", () -> TEST_ROOT + "/data");
        registry.add("workbench.home-dir", () -> TEST_ROOT);
    }

    @Autowired private MockMvc mockMvc;
    @Autowired private JdbcTemplate jdbcTemplate;
    private MockHttpSession session;
    private String today() { return TimeUtil.today("Asia/Shanghai"); }
    private String tomorrow() { return TimeUtil.format(java.time.LocalDate.now().plusDays(1)); }

    @BeforeEach
    void setUp() {
        jdbcTemplate.update("DELETE FROM schedule_event");
        jdbcTemplate.update("DELETE FROM activity_log");
        jdbcTemplate.update("UPDATE app_config SET config_value='true' WHERE config_key='app.initialized'");
        jdbcTemplate.update("UPDATE app_config SET config_value='Asia/Shanghai' WHERE config_key='app.timezone'");
        session = new MockHttpSession();
        session.setAttribute(AuthConstants.SESSION_USER, "tester");
    }

    @Test
    void createsListsUpdatesAndSoftDeletesEventsByDate() throws Exception {
        mockMvc.perform(post("/api/v1/events").session(session)
                        .contentType("application/json")
                        .content("{\"title\":\"团队站会\",\"type\":\"meeting\",\"date\":\"" + today() + "\",\"start\":\"09:30\",\"end\":\"10:00\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.event.date").value(today()))
                .andExpect(jsonPath("$.data.warnings.length()").value(0));

        long id = jdbcTemplate.queryForObject("SELECT id FROM schedule_event WHERE title='团队站会'", Long.class).longValue();
        mockMvc.perform(patch("/api/v1/events/{id}", id).session(session)
                        .contentType("application/json").content("{\"start\":\"10:00\",\"end\":\"10:30\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.event.start").value("10:00"));

        mockMvc.perform(post("/api/v1/events").session(session)
                        .contentType("application/json")
                        .content("{\"title\":\"明天事项\",\"type\":\"other\",\"date\":\"" + tomorrow() + "\"}"))
                .andExpect(status().isOk());
        mockMvc.perform(get("/api/v1/events").session(session))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.length()").value(1));
        mockMvc.perform(get("/api/v1/events").param("date", tomorrow()).session(session))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.length()").value(1));

        mockMvc.perform(delete("/api/v1/events/{id}", id).session(session)).andExpect(status().isOk());
        mockMvc.perform(get("/api/v1/events").session(session))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.length()").value(0));
        assertThat(jdbcTemplate.queryForObject("SELECT deleted FROM schedule_event WHERE id=?", Integer.class, id)).isEqualTo(1);
    }

    /**
     * 冲突检测的对象是「同一天」的日程，所以这里必须显式带上日期：
     * 不带 date 现在表示「时间待定」（US-4.2），待定日程不参与冲突检测。
     */
    @Test
    void warnsOnPartialAndFullOverlapButNotOnTouchingBoundaries() throws Exception {
        insertEvent("站会", today(), "09:30", "10:00");
        insertEvent("相邻事项", today(), "10:00", "10:30");
        // 边界相接不算冲突
        mockMvc.perform(post("/api/v1/events").session(session)
                        .contentType("application/json")
                        .content("{\"title\":\"接续事项\",\"date\":\"" + today() + "\",\"start\":\"10:30\",\"end\":\"11:00\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.warnings.length()").value(0));
        // 部分重叠
        mockMvc.perform(post("/api/v1/events").session(session)
                        .contentType("application/json")
                        .content("{\"title\":\"部分重叠\",\"date\":\"" + today() + "\",\"start\":\"09:45\",\"end\":\"10:15\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.warnings.length()").value(2));
        // 完全重叠
        mockMvc.perform(post("/api/v1/events").session(session)
                        .contentType("application/json")
                        .content("{\"title\":\"完全重叠\",\"date\":\"" + today() + "\",\"start\":\"09:30\",\"end\":\"10:00\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.warnings[0]").value(org.hamcrest.Matchers.containsString("站会")));
    }

    @Test
    void rejectsCrossDayOrReversedTimeAndInvalidType() throws Exception {
        mockMvc.perform(post("/api/v1/events").session(session)
                        .contentType("application/json")
                        .content("{\"title\":\"跨日\",\"start\":\"23:30\",\"end\":\"00:30\"}"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value(3003));
        mockMvc.perform(post("/api/v1/events").session(session)
                        .contentType("application/json")
                        .content("{\"title\":\"反向\",\"start\":\"10:00\",\"end\":\"10:00\"}"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value(3003));
        mockMvc.perform(post("/api/v1/events").session(session)
                        .contentType("application/json")
                        .content("{\"title\":\"坏类型\",\"type\":\"party\"}"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value(1002));
    }

    /**
     * 按日查询的 date 参数可能被手改地址栏、或被前端日期选择器驱动，格式非法时要给出能照做的提示，
     * 而不是落到 500 兜底（那样用户只看到「系统暂时不可用」，不知道要改成什么格式）。
     */
    @Test
    void malformedDateQueryTellsExpectedFormatAndBlankMeansToday() throws Exception {
        for (String bad : new String[] {"2026-9-12", "20260912", "2026/09/12"}) {
            mockMvc.perform(get("/api/v1/events").param("date", bad).session(session))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value(1002))
                    .andExpect(jsonPath("$.message").value("日期格式不正确，请按 YYYY-MM-DD 填写"));
        }
        // 省略 date 与传空串都表示「今天」，不该报格式错误。
        insertEvent("今天的会", today(), "09:30", "10:00");
        mockMvc.perform(get("/api/v1/events").session(session))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.length()").value(1));
        mockMvc.perform(get("/api/v1/events").param("date", "").session(session))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.length()").value(1));
    }

    @Test
    void dashboardReturnsTodayEventsAndCount() throws Exception {
        insertEvent("团队站会", today(), "09:30", "10:00");
        insertEvent("深度块", today(), "10:00", "11:30");
        insertEvent("明天事项", tomorrow(), "14:00", "15:00");
        mockMvc.perform(get("/api/v1/dashboard/today").session(session))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.metrics.eventToday").value(2))
                .andExpect(jsonPath("$.data.events.length()").value(2))
                .andExpect(jsonPath("$.data.events[0].title").value("团队站会"));
    }

    /**
     * US-4.2：整理时抽不出日期的日程不该被堵死。
     * 不填日期 => 落进「待定时间」；补上日期 => 自动排进那一天；再清空 => 回到待定。
     */
    @Test
    void keepsUndatedEventInPendingUntilDateIsFilled() throws Exception {
        mockMvc.perform(post("/api/v1/events").session(session)
                        .contentType("application/json")
                        .content("{\"title\":\"和业务对齐\",\"type\":\"meeting\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.event.date").value(org.hamcrest.Matchers.nullValue()))
                .andExpect(jsonPath("$.data.warnings.length()").value(0));

        long id = jdbcTemplate.queryForObject("SELECT id FROM schedule_event WHERE title='和业务对齐'", Long.class).longValue();

        mockMvc.perform(get("/api/v1/events/pending").session(session))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(1))
                .andExpect(jsonPath("$.data[0].title").value("和业务对齐"));
        // 待定日程不能混进「今天」的时间线
        mockMvc.perform(get("/api/v1/events").session(session))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.length()").value(0));

        // 补上日期后离开待定区
        mockMvc.perform(patch("/api/v1/events/{id}", id).session(session)
                        .contentType("application/json").content("{\"date\":\"" + tomorrow() + "\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.event.date").value(tomorrow()));
        mockMvc.perform(get("/api/v1/events/pending").session(session))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.length()").value(0));
        mockMvc.perform(get("/api/v1/events").param("date", tomorrow()).session(session))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.length()").value(1));

        // 再把日期清空：这里以前会抛「日期格式不正确」，用户在编辑面板里根本清不掉日期
        mockMvc.perform(patch("/api/v1/events/{id}", id).session(session)
                        .contentType("application/json").content("{\"date\":\"\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.event.date").value(org.hamcrest.Matchers.nullValue()));
        mockMvc.perform(get("/api/v1/events/pending").session(session))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.length()").value(1));
        mockMvc.perform(get("/api/v1/events").param("date", tomorrow()).session(session))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.length()").value(0));
        assertThat(jdbcTemplate.queryForObject("SELECT event_date FROM schedule_event WHERE id=?", String.class, id)).isNull();
    }

    /** 待定日程既不该进驾驶舱计数，也不该参与冲突检测——它还没有落在任何一天上。 */
    @Test
    void pendingEventStaysOutOfDashboardAndConflictDetection() throws Exception {
        insertPendingEvent("待定的客户沟通");
        insertEvent("站会", today(), "09:30", "10:00");

        mockMvc.perform(get("/api/v1/dashboard/today").session(session))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.metrics.eventToday").value(1))
                .andExpect(jsonPath("$.data.events.length()").value(1));

        mockMvc.perform(post("/api/v1/events").session(session)
                        .contentType("application/json")
                        .content("{\"title\":\"重叠事项\",\"date\":\"" + today() + "\",\"start\":\"09:45\",\"end\":\"10:15\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.warnings.length()").value(1));

        // 待定日程就算带了开始时间，也不该跟今天的事项报重叠
        mockMvc.perform(post("/api/v1/events").session(session)
                        .contentType("application/json")
                        .content("{\"title\":\"待定但带时间\",\"start\":\"09:45\",\"end\":\"10:15\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.warnings.length()").value(0));
    }

    /**
     * 日程检索（2026-09-12）：命中范围是**全部日期**，且必须包含「待定时间」区。
     * 用户在日程页搜索时往往并不知道那条日程落在哪一天（「上个月和客户的会」），
     * 只搜「正在看的那天」会出现「搜了但没有」——那比不提供搜索更糟，用户会以为它不存在。
     */
    @Test
    void searchHitsAcrossDatesIncludingPendingOnes() throws Exception {
        insertEvent("团队站会", today(), "09:30", "10:00");
        insertEvent("客户对齐", tomorrow(), "14:00", "15:00");
        insertPendingEvent("客户回访");

        // 跨日期命中：今天的「团队站会」与明天的「客户对齐」都能搜到
        mockMvc.perform(get("/api/v1/events").param("q", "团队").session(session))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.length()").value(1));
        mockMvc.perform(get("/api/v1/events").param("q", "客户").session(session))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.length()").value(2));
        // 待定日程（event_date IS NULL）同样在命中范围内，且排在最后
        mockMvc.perform(get("/api/v1/events").param("q", "回访").session(session))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(1))
                .andExpect(jsonPath("$.data[0].date").value(org.hamcrest.Matchers.nullValue()));
        // 时间段也能检索：搜「09:30」应当命中今天的站会
        mockMvc.perform(get("/api/v1/events").param("q", "09:30").session(session))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.length()").value(2));
    }

    /**
     * 检索优先于按日：同时传 date 与 q 时按检索走。
     * 另外空白的 q 不能当成「搜全部」——前端清空搜索框的那一刻会闪出一整库日程，看起来像搜索坏了，
     * 所以空白 q 要退回「按日视图」（省略 date 时即今天）。
     */
    @Test
    void searchTakesPrecedenceOverDateAndBlankQueryFallsBackToDayView() throws Exception {
        insertEvent("今天的会", today(), "09:30", "10:00");
        insertEvent("明天的会", tomorrow(), "14:00", "15:00");

        mockMvc.perform(get("/api/v1/events").param("date", tomorrow()).param("q", "今天").session(session))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(1))
                .andExpect(jsonPath("$.data[0].title").value("今天的会"));

        for (String blank : new String[] {"", "   "}) {
            mockMvc.perform(get("/api/v1/events").param("q", blank).session(session))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.length()").value(1))
                    .andExpect(jsonPath("$.data[0].title").value("今天的会"));
        }
    }

    /** 用户输入里的通配符必须当普通字符：否则「搜 %」会变成「搜全部」，而日志里没有任何异常。 */
    @Test
    void searchTreatsLikeWildcardsAsLiteralCharacters() throws Exception {
        insertEvent("进度 100% 完成", today(), null, null);
        insertEvent("字段 A_B 说明", today(), null, null);

        mockMvc.perform(get("/api/v1/events").param("q", "%").session(session))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(1))
                .andExpect(jsonPath("$.data[0].title").value("进度 100% 完成"));
        mockMvc.perform(get("/api/v1/events").param("q", "_").session(session))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(1))
                .andExpect(jsonPath("$.data[0].title").value("字段 A_B 说明"));
    }

    /** 软删掉的日程不该被搜出来（它是进回收站了，不是不存在，但列表上不该出现）。 */
    @Test
    void searchSkipsDeletedEvents() throws Exception {
        insertEvent("已删掉的会", today(), "09:30", "10:00");
        long id = jdbcTemplate.queryForObject("SELECT id FROM schedule_event WHERE title='已删掉的会'", Long.class).longValue();
        mockMvc.perform(delete("/api/v1/events/{id}", id).session(session)).andExpect(status().isOk());

        mockMvc.perform(get("/api/v1/events").param("q", "已删掉").session(session))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.length()").value(0));
    }

    /**
     * 周视图的取数口：一次拿一整段区间（日程页一次要 7 天，按天循环调会打 7 个请求）。
     *
     * <p>三条边界都要守住：<b>首尾两天都含在内</b>（七行视图里少了周一或周日，用户会以为那天没日程）、
     * <b>同一天内没有开始时间的日程也要返回</b>（它同样是那天的事，只是没排具体时段）、
     * <b>待定日程（event_date 为 NULL）不能被卷进来</b>（它不属于任何一周，只在「待定时间」区露出）。</p>
     */
    @Test
    void rangeQueryCoversBothBoundariesAndKeepsUndatedEventsOut() throws Exception {
        insertEvent("区间首日", "2026-09-07", "09:00", "10:00");
        insertEvent("区间内无具体时间", "2026-09-09", null, null);
        insertEvent("区间末日", "2026-09-13", "20:00", "21:00");
        insertEvent("区间前一日", "2026-09-06", "09:00", "10:00");
        insertEvent("区间后一日", "2026-09-14", "09:00", "10:00");
        insertPendingEvent("待定日程");

        mockMvc.perform(get("/api/v1/events")
                        .param("from", "2026-09-07").param("to", "2026-09-13").session(session))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(3))
                .andExpect(jsonPath("$.data[0].title").value("区间首日"))
                .andExpect(jsonPath("$.data[1].title").value("区间内无具体时间"))
                .andExpect(jsonPath("$.data[2].title").value("区间末日"));

        // 单日区间也必须成立（「上一周 / 下一周」推到只有一天数据的一周时不会空手而归）
        mockMvc.perform(get("/api/v1/events")
                        .param("from", "2026-09-09").param("to", "2026-09-09").session(session))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(1))
                .andExpect(jsonPath("$.data[0].title").value("区间内无具体时间"));
    }

    /**
     * 区间参数的两个典型误用都要给出能照做的中文提示，而不是 500，
     * 也不要做「另一半默认等于自己」这种隐式兜底 —— 用户少打一个参数会拿到一个
     * 看起来正常、其实不是他要的区间，界面上没有任何迹象可循。
     */
    @Test
    void rangeQueryRequiresBothEndsAndRejectsReversedOrMalformedRange() throws Exception {
        mockMvc.perform(get("/api/v1/events").param("from", "2026-09-07").session(session))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(1002))
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("同时提供 from 与 to")));
        mockMvc.perform(get("/api/v1/events").param("to", "2026-09-13").session(session))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(1002))
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("同时提供 from 与 to")));
        mockMvc.perform(get("/api/v1/events")
                        .param("from", "2026-09-13").param("to", "2026-09-07").session(session))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(1002))
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("开始日期不能晚于结束日期")));
        // 日期格式的文案与按日查询（malformedDateQueryTellsExpectedFormatAndBlankMeansToday）逐字一致
        mockMvc.perform(get("/api/v1/events")
                        .param("from", "2026/09/07").param("to", "2026-09-13").session(session))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(1002))
                .andExpect(jsonPath("$.message").value("日期格式不正确，请按 YYYY-MM-DD 填写"));
    }

    /** 检索优先于区间：用户既然在搜，命中范围就该是全部日期，而不是「只在这一周里搜」。 */
    @Test
    void searchTakesPrecedenceOverRangeQuery() throws Exception {
        insertEvent("区间外的客户会", "2026-08-01", "09:00", "10:00");
        insertEvent("区间内的客户会", "2026-09-09", "09:00", "10:00");

        mockMvc.perform(get("/api/v1/events")
                        .param("from", "2026-09-07").param("to", "2026-09-13").param("q", "客户").session(session))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(2));
    }

    /**
     * 「每周三做什么」这类周期日程：一次生成连续 N 周的同一天。
     *
     * <p>三条要点：**物化成独立记录**（所以「只改其中某一次」才成立）、
     * **组标识就是首期的 id**（前端靠它显示「每周 · 共 4 次」）、
     * **只写一条时间线**（用户做的是「一件事」，不该在流水里留四条）。</p>
     */
    @Test
    void repeatWeeksMaterialisesIndependentWeeklyOccurrences() throws Exception {
        mockMvc.perform(post("/api/v1/events").session(session)
                        .contentType("application/json")
                        .content("{\"title\":\"周三周会\",\"type\":\"meeting\",\"date\":\"2026-09-16\",\"start\":\"09:00\",\"end\":\"10:00\",\"repeatWeeks\":4}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.event.repeatTotal").value(4));

        List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                "SELECT id, event_date, repeat_group, repeat_total FROM schedule_event WHERE title='周三周会' ORDER BY event_date");
        assertThat(rows).hasSize(4);
        assertThat(rows.get(0).get("event_date")).isEqualTo("2026-09-16");
        assertThat(rows.get(1).get("event_date")).isEqualTo("2026-09-23");
        assertThat(rows.get(2).get("event_date")).isEqualTo("2026-09-30");
        assertThat(rows.get(3).get("event_date")).isEqualTo("2026-10-07");
        long group = ((Number) rows.get(0).get("repeat_group")).longValue();
        assertThat(group).isEqualTo(((Number) rows.get(0).get("id")).longValue());
        for (Map<String, Object> row : rows) {
            assertThat(((Number) row.get("repeat_group")).longValue()).isEqualTo(group);
            assertThat(((Number) row.get("repeat_total")).intValue()).isEqualTo(4);
        }
        // 时间线只留一条，并写明这是周期日程
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM activity_log WHERE content LIKE '%周三周会%' AND content LIKE '%每周%'", Integer.class)).isEqualTo(1);

        // **只改其中一期**：另外三期必须原样不动 —— 这正是用户要求的语义
        long second = ((Number) rows.get(1).get("id")).longValue();
        mockMvc.perform(patch("/api/v1/events/{id}", second).session(session)
                        .contentType("application/json").content("{\"start\":\"14:00\",\"end\":\"15:00\"}"))
                .andExpect(status().isOk());
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM schedule_event WHERE repeat_group=? AND start_time='09:00'", Integer.class, group)).isEqualTo(3);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM schedule_event WHERE repeat_group=? AND start_time='14:00'", Integer.class, group)).isEqualTo(1);

        // 区间查询能把后面几期一并取回（周视图跨月翻页靠的就是这个）
        mockMvc.perform(get("/api/v1/events").param("from", "2026-09-28").param("to", "2026-10-11").session(session))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(2));
    }

    /**
     * 重复期数的两个边界：越界要被字段校验拦住并给出**能照做**的中文原因；
     * 待定日程（没日期）一律不重复 —— 没有日期就谈不上「每周的同一天」，硬展开只能编一个日期出来。
     */
    @Test
    void repeatWeeksValidatesRangeAndNeverRepeatsPendingEvents() throws Exception {
        mockMvc.perform(post("/api/v1/events").session(session)
                        .contentType("application/json")
                        .content("{\"title\":\"超上限\",\"date\":\"2026-09-16\",\"repeatWeeks\":13}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(1002))
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("最多 12 期")));
        mockMvc.perform(post("/api/v1/events").session(session)
                        .contentType("application/json")
                        .content("{\"title\":\"零期\",\"date\":\"2026-09-16\",\"repeatWeeks\":0}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(1002));

        mockMvc.perform(post("/api/v1/events").session(session)
                        .contentType("application/json")
                        .content("{\"title\":\"待定重复\",\"repeatWeeks\":4}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.event.repeatTotal").value(1));
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM schedule_event WHERE title='待定重复'", Integer.class)).isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM schedule_event WHERE title='待定重复' AND event_date IS NULL", Integer.class)).isEqualTo(1);
    }

    private void insertPendingEvent(String title) {
        jdbcTemplate.update("INSERT INTO schedule_event(title,event_type,event_date,start_time,end_time,created_at,updated_at,is_demo) VALUES (?,?,NULL,?,?,?,?,0)",
                title, "meeting", "09:30", "10:00", TimeUtil.now("Asia/Shanghai"), TimeUtil.now("Asia/Shanghai"));
    }

    private void insertEvent(String title, String date, String start, String end) {
        jdbcTemplate.update("INSERT INTO schedule_event(title,event_type,event_date,start_time,end_time,created_at,updated_at,is_demo) VALUES (?,?,?,?,?,?,?,0)",
                title, "meeting", date, start, end, TimeUtil.now("Asia/Shanghai"), TimeUtil.now("Asia/Shanghai"));
    }
}
