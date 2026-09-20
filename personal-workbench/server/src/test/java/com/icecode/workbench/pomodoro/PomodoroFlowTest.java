package com.icecode.workbench.pomodoro;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
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
class PomodoroFlowTest {

    private static final String TEST_ROOT = "target/test-workbench/pomodoro-" + UUID.randomUUID().toString();

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("workbench.data-dir", () -> TEST_ROOT + "/data");
        registry.add("workbench.home-dir", () -> TEST_ROOT);
    }

    @Autowired private MockMvc mockMvc;
    @Autowired private JdbcTemplate jdbcTemplate;
    private MockHttpSession session;

    private String today() { return TimeUtil.today("Asia/Shanghai"); }

    @BeforeEach
    void setUp() {
        jdbcTemplate.update("DELETE FROM pomodoro");
        jdbcTemplate.update("DELETE FROM activity_log");
        jdbcTemplate.update("DELETE FROM daily_plan");
        jdbcTemplate.update("DELETE FROM inbox_item");
        jdbcTemplate.update("DELETE FROM task");
        jdbcTemplate.update("UPDATE app_config SET config_value='true' WHERE config_key='app.initialized'");
        jdbcTemplate.update("UPDATE app_config SET config_value='Asia/Shanghai' WHERE config_key='app.timezone'");
        jdbcTemplate.update("UPDATE app_config SET config_value='25' WHERE config_key='pomo.work'");
        jdbcTemplate.update("UPDATE app_config SET config_value='5' WHERE config_key='pomo.short'");
        jdbcTemplate.update("UPDATE app_config SET config_value='15' WHERE config_key='pomo.long'");
        jdbcTemplate.update("UPDATE app_config SET config_value='false' WHERE config_key='pomo.auto'");
        session = new MockHttpSession();
        session.setAttribute(AuthConstants.SESSION_USER, "tester");
    }

    @Test
    void completesPomodoroWithTaskAndWritesActivity() throws Exception {
        jdbcTemplate.update("INSERT INTO task(title, priority, status, created_at) VALUES ('写周报','P1','doing',?)",
                TimeUtil.now("Asia/Shanghai"));
        long taskId = jdbcTemplate.queryForObject("SELECT id FROM task WHERE title='写周报'", Long.class).longValue();

        mockMvc.perform(post("/api/v1/pomodoros").session(session)
                        .contentType("application/json")
                        .content("{\"taskId\":" + taskId + ",\"minutes\":25}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.count").value(1))
                .andExpect(jsonPath("$.data.minutes").value(25));

        Integer pomoRows = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM pomodoro WHERE task_id=? AND minutes=25", Integer.class, taskId);
        org.assertj.core.api.Assertions.assertThat(pomoRows.intValue()).isEqualTo(1);
        String activity = jdbcTemplate.queryForObject(
                "SELECT content FROM activity_log WHERE log_type='pomo'", String.class);
        org.assertj.core.api.Assertions.assertThat(activity).contains("写周报").contains("25");

        mockMvc.perform(post("/api/v1/pomodoros").session(session)
                        .contentType("application/json").content("{\"minutes\":50}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.count").value(2))
                .andExpect(jsonPath("$.data.minutes").value(75));

        mockMvc.perform(get("/api/v1/pomodoros/today").session(session))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.count").value(2))
                .andExpect(jsonPath("$.data.minutes").value(75));
    }

    @Test
    void rejectsPomodoroWithMissingTaskOrBadMinutes() throws Exception {
        mockMvc.perform(post("/api/v1/pomodoros").session(session)
                        .contentType("application/json").content("{\"taskId\":99999,\"minutes\":25}"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value(1001));
        mockMvc.perform(post("/api/v1/pomodoros").session(session)
                        .contentType("application/json").content("{\"minutes\":0}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(1002));
        mockMvc.perform(post("/api/v1/pomodoros").session(session)
                        .contentType("application/json").content("{\"minutes\":121}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(1002));
    }

    @Test
    void readsAndSavesPomodoroConfig() throws Exception {
        mockMvc.perform(get("/api/v1/pomodoros/config").session(session))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.work").value(25))
                .andExpect(jsonPath("$.data.shortBreak").value(5))
                .andExpect(jsonPath("$.data.longBreak").value(15))
                .andExpect(jsonPath("$.data.auto").value(false));

        mockMvc.perform(put("/api/v1/pomodoros/config").session(session)
                        .contentType("application/json")
                        .content("{\"work\":50,\"shortBreak\":10,\"longBreak\":30,\"auto\":true}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.work").value(50))
                .andExpect(jsonPath("$.data.auto").value(true));

        mockMvc.perform(get("/api/v1/pomodoros/config").session(session))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.work").value(50))
                .andExpect(jsonPath("$.data.shortBreak").value(10))
                .andExpect(jsonPath("$.data.longBreak").value(30))
                .andExpect(jsonPath("$.data.auto").value(true));
    }

    @Test
    void dashboardSummarizesInboxAndPomodoroWithoutTheme() throws Exception {
        // 2026-09-20：「今日主题」整体下线（用户反馈没什么用），本用例原先还顺带断言
        // 主题的写入 / 幂等 / 回显，现在改成只守驾驶舱汇总的口径 + 反向守住接口不再存在。
        mockMvc.perform(post("/api/v1/dashboard/theme").session(session)
                        .contentType("application/json").content("{\"theme\":\"这不该再能写入\"}"))
                .andExpect(status().isNotFound());

        jdbcTemplate.update("INSERT INTO inbox_item(raw_content, status, created_at) VALUES ('明天下午三点开会','pending',?)",
                TimeUtil.now("Asia/Shanghai"));
        jdbcTemplate.update("INSERT INTO pomodoro(task_id, minutes, ended_at, created_at) VALUES (NULL,25,?,?)",
                TimeUtil.now("Asia/Shanghai"), TimeUtil.now("Asia/Shanghai"));

        mockMvc.perform(get("/api/v1/dashboard/today").session(session))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.theme").doesNotExist())
                .andExpect(jsonPath("$.data.metrics.inboxPending").value(1))
                .andExpect(jsonPath("$.data.metrics.pomoToday").value(1))
                .andExpect(jsonPath("$.data.brief").value(org.hamcrest.Matchers.containsString("收集箱还有 1 条待整理")));
    }

    @Test
    void listsTimelineWithDayGroupAndLimitCap() throws Exception {
        for (int i = 0; i < 5; i++) {
            jdbcTemplate.update("INSERT INTO activity_log(log_type, content, created_at) VALUES ('inbox','记录 " + i + "',?)",
                    TimeUtil.now("Asia/Shanghai"));
        }
        mockMvc.perform(get("/api/v1/activity").session(session))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(5))
                .andExpect(jsonPath("$.data[0].day").value(today()))
                .andExpect(jsonPath("$.data[0].type").value("inbox"))
                .andExpect(jsonPath("$.data[0].content").value("记录 4"));

        mockMvc.perform(get("/api/v1/activity").param("limit", "3").session(session))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(3));

        mockMvc.perform(get("/api/v1/activity").param("limit", "99999").session(session))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(5));
    }
}
