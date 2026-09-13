package com.icecode.workbench.task;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
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

@SpringBootTest
@AutoConfigureMockMvc
class TaskFlowTest {

    private static final String TEST_ROOT = "target/test-workbench/task-" + UUID.randomUUID().toString();

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("workbench.data-dir", () -> TEST_ROOT + "/data");
        registry.add("workbench.home-dir", () -> TEST_ROOT);
    }

    @Autowired private MockMvc mockMvc;
    @Autowired private JdbcTemplate jdbcTemplate;
    private MockHttpSession session;
    private String tomorrow() { return TimeUtil.format(LocalDate.now().plusDays(1)); }
    private String yesterday() { return TimeUtil.format(LocalDate.now().minusDays(1)); }
    private String nowText() { return TimeUtil.now("Asia/Shanghai"); }

    @BeforeEach
    void setUp() {
        jdbcTemplate.update("DELETE FROM daily_plan_item");
        jdbcTemplate.update("DELETE FROM pomodoro");
        jdbcTemplate.update("DELETE FROM activity_log");
        jdbcTemplate.update("DELETE FROM task");
        jdbcTemplate.update("UPDATE app_config SET config_value='true' WHERE config_key='app.initialized'");
        jdbcTemplate.update("UPDATE app_config SET config_value='Asia/Shanghai' WHERE config_key='app.timezone'");
        session = new MockHttpSession();
        session.setAttribute(AuthConstants.SESSION_USER, "tester");
    }

    @Test
    void createsListsUpdatesCompletesAndSoftDeletesTask() throws Exception {
        mockMvc.perform(post("/api/v1/tasks").session(session)
                        .contentType("application/json")
                        .content("{\"title\":\" 完成 M3 切片 \",\"description\":\"接通真实数据\",\"priority\":\"P0\",\"due\":\"" + tomorrow() + "\",\"deep\":true,\"blocking\":false,\"note\":\"先写测试\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.title").value("完成 M3 切片"))
                .andExpect(jsonPath("$.data.status").value("todo"))
                .andExpect(jsonPath("$.data.priority").value("P0"));

        Long id = jdbcTemplate.queryForObject("SELECT id FROM task WHERE title='完成 M3 切片'", Long.class);
        mockMvc.perform(patch("/api/v1/tasks/{id}", id).session(session)
                        .contentType("application/json")
                        .content("{\"priority\":\"P1\",\"blocking\":true,\"note\":\"已更新\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.priority").value("P1"))
                .andExpect(jsonPath("$.data.blocking").value(true));

        mockMvc.perform(post("/api/v1/tasks/{id}/status", id).session(session)
                        .contentType("application/json").content("{\"status\":\"doing\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("doing"));
        mockMvc.perform(post("/api/v1/tasks/{id}/status", id).session(session)
                        .contentType("application/json").content("{\"status\":\"done\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("done"))
                .andExpect(jsonPath("$.data.completedAt").isNotEmpty())
                .andExpect(jsonPath("$.data.overdue").value(false));

        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM activity_log WHERE log_type='praise' AND content LIKE '%完成 M3 切片%'", Integer.class)).isEqualTo(1);

        mockMvc.perform(delete("/api/v1/tasks/{id}", id).session(session)).andExpect(status().isOk());
        mockMvc.perform(get("/api/v1/tasks").session(session))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(0));
        assertThat(jdbcTemplate.queryForObject("SELECT deleted FROM task WHERE id=?", Integer.class, id)).isEqualTo(1);
    }

    @Test
    void validatesPriorityDateAndStateTransitions() throws Exception {
        mockMvc.perform(post("/api/v1/tasks").session(session)
                        .contentType("application/json").content("{\"title\":\"非法优先级\",\"priority\":\"P9\"}"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value(1002));
        mockMvc.perform(post("/api/v1/tasks").session(session)
                        .contentType("application/json").content("{\"title\":\"非法日期\",\"priority\":\"P2\",\"due\":\"2026-02-30\"}"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value(1002));

        long id = insertTask("状态机任务", "todo", tomorrow(), "P2", 0, 0, 0);
        mockMvc.perform(post("/api/v1/tasks/{id}/status", id).session(session)
                        .contentType("application/json").content("{\"status\":\"done\"}"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value(3001));
        mockMvc.perform(post("/api/v1/tasks/{id}/status", id).session(session)
                        .contentType("application/json").content("{\"status\":\"doing\"}"))
                .andExpect(status().isOk());
        mockMvc.perform(post("/api/v1/tasks/{id}/status", id).session(session)
                        .contentType("application/json").content("{\"status\":\"done\"}"))
                .andExpect(status().isOk());
        mockMvc.perform(post("/api/v1/tasks/{id}/status", id).session(session)
                        .contentType("application/json").content("{\"status\":\"doing\"}"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value(3001));
        mockMvc.perform(post("/api/v1/tasks/{id}/postpone", id).session(session))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value(3002));
        mockMvc.perform(post("/api/v1/tasks/{id}/status", id).session(session)
                        .contentType("application/json").content("{\"status\":\"todo\"}"))
                .andExpect(status().isOk());
    }

    @Test
    void postponesAcrossMonthAndYearAndTracksRepeatedPostpones() throws Exception {
        long monthEnd = insertTask("跨月任务", "todo", "2026-01-31", "P2", 0, 0, 0);
        long yearEnd = insertTask("跨年任务", "todo", "2026-12-31", "P2", 0, 0, 0);

        mockMvc.perform(post("/api/v1/tasks/{id}/postpone", monthEnd).session(session))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.due").value("2026-02-01"));
        mockMvc.perform(post("/api/v1/tasks/{id}/postpone", yearEnd).session(session))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.due").value("2027-01-01"));
        mockMvc.perform(post("/api/v1/tasks/{id}/postpone", monthEnd).session(session)).andExpect(status().isOk());
        mockMvc.perform(post("/api/v1/tasks/{id}/postpone", monthEnd).session(session))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.postponed").value(3));
    }

    @Test
    void dashboardRanksOverdueBeforeBlockingDeadlineAndDeepWork() throws Exception {
        insertTask("深度工作", "todo", null, "P0", 1, 0, 0);
        insertTask("明天截止", "todo", tomorrow(), "P0", 0, 0, 0);
        insertTask("阻塞他人", "todo", null, "P3", 0, 1, 0);
        insertTask("已逾期", "todo", yesterday(), "P3", 0, 0, 0);

        mockMvc.perform(get("/api/v1/dashboard/today").session(session))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.metrics.taskTotal").value(4))
                .andExpect(jsonPath("$.data.metrics.overdue").value(1))
                .andExpect(jsonPath("$.data.topTasks[0].title").value("已逾期"))
                .andExpect(jsonPath("$.data.topTasks[0].reason").value("已逾期 1 天"))
                .andExpect(jsonPath("$.data.topTasks[1].title").value("阻塞他人"))
                .andExpect(jsonPath("$.data.topTasks[2].title").value("明天截止"));
    }

    @Test
    void treatsPercentAndUnderscoreAsLiteralSearchCharacters() throws Exception {
        insertTask("处理 100% 回归", "todo", null, "P2", 0, 0, 0);
        insertTask("核对 A_B 字段", "todo", null, "P2", 0, 0, 0);
        insertTask("普通任务", "todo", null, "P2", 0, 0, 0);
        mockMvc.perform(get("/api/v1/tasks").param("keyword", "%").session(session))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.length()").value(1));
        mockMvc.perform(get("/api/v1/tasks").param("keyword", "_").session(session))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.length()").value(1));
    }

    private long insertTask(String title, String status, String due, String priority, int deep, int blocking, int postponed) {
        jdbcTemplate.update("INSERT INTO task(title,priority,status,due_date,is_deep_work,is_blocking,postponed,created_at,updated_at,is_demo) VALUES (?,?,?,?,?,?,?,?,?,0)",
                title, priority, status, due, deep, blocking, postponed, nowText(), nowText());
        return jdbcTemplate.queryForObject("SELECT id FROM task WHERE title=?", Long.class, title).longValue();
    }
}
