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

    /**
     * 工作 / 生活分组（2026-09-14）。用户的诉求是「能筛选工作和生活，默认选择工作」。
     *
     * <p>这里守两件事：① {@code ?grp=} 真的在**取数层**过滤；② 不传 grp 时返回全部 ——
     * 后者是给「全部」视图用的，不能被哪个默认值悄悄收窄。</p>
     */
    @Test
    void filtersTasksByWorkAndLifeGroup() throws Exception {
        insertTask("写限流方案", "todo", null, "P0", 0, 0, 0, "work");
        insertTask("评审 MR", "todo", null, "P1", 0, 0, 0, "work");
        insertTask("买奶粉", "todo", null, "P2", 0, 0, 0, "life");

        mockMvc.perform(get("/api/v1/tasks").param("grp", "work").session(session))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.length()").value(2));
        mockMvc.perform(get("/api/v1/tasks").param("grp", "life").session(session))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(1))
                .andExpect(jsonPath("$.data[0].title").value("买奶粉"))
                .andExpect(jsonPath("$.data[0].grp").value("life"));
        // 不传 grp = 全部（「全部」分组视图走这条路）
        mockMvc.perform(get("/api/v1/tasks").session(session))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.length()").value(3));
    }

    /**
     * 分组与检索是**两个独立条件**，必须一起生效。
     *
     * <p>这条是有针对性的：任务检索与分组都收在 {@code TaskService.list} 里下传给 SQL，
     * 一旦哪次重构把它俩写成一个 OR（或后者覆盖前者），用户就会在「工作」里搜出生活任务，
     * 而界面上的提示条还写着「在工作分组里搜索」，自相矛盾却看不出是谁错了。</p>
     */
    @Test
    void combinesGroupFilterWithKeywordSearch() throws Exception {
        insertTask("方案定稿", "todo", null, "P0", 0, 0, 0, "work");
        insertTask("家里的方案（装修）", "todo", null, "P2", 0, 0, 0, "life");

        mockMvc.perform(get("/api/v1/tasks").param("grp", "work").param("keyword", "方案").session(session))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(1))
                .andExpect(jsonPath("$.data[0].title").value("方案定稿"));
    }

    @Test
    void rejectsUnknownTaskGroup() throws Exception {
        mockMvc.perform(get("/api/v1/tasks").param("grp", "family").session(session))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(1002))
                // 报错必须能指导操作：说清合法取值，而不是笼统的「参数不正确」
                .andExpect(jsonPath("$.message").value("任务分组只能填 work（工作）或 life（生活）"));
    }

    /**
     * 创建时留空 → 按关键词自动判定；显式传 life → 用传进来的值。
     *
     * <p>判定复用 {@code MemoService.autoGroup}（工作关键词命中判 work，否则 life），
     * 也就是**判定规则只有一份实现**。这里两条例子里，「买奶粉」是生活、
     * 「评审方案」命中工作关键词 —— 如果哪天把判定抄成第二份，这两条会先红。</p>
     */
    @Test
    void autoDetectsGroupOnCreateButHonoursExplicitValue() throws Exception {
        mockMvc.perform(post("/api/v1/tasks").session(session)
                        .contentType("application/json").content("{\"title\":\"买奶粉和湿巾\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.grp").value("life"));

        mockMvc.perform(post("/api/v1/tasks").session(session)
                        .contentType("application/json").content("{\"title\":\"评审一遍限流方案\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.grp").value("work"));

        // 显式传值优先于判定：标题看着像生活，但用户就是要归到工作
        mockMvc.perform(post("/api/v1/tasks").session(session)
                        .contentType("application/json").content("{\"title\":\"买奶粉顺路问下客户\",\"grp\":\"work\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.grp").value("work"));

        // auto 与留空同义
        mockMvc.perform(post("/api/v1/tasks").session(session)
                        .contentType("application/json").content("{\"title\":\"取快递\",\"grp\":\"auto\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.grp").value("life"));
    }

    /**
     * 卡片上的分组徽标走 {@code POST /{id}/group}：一键切换、幂等、且只动分组这一格。
     *
     * <p>幂等那条是有意写的：徽标是「点一下切到另一侧」，用户点到已经在的那一侧不该报错，
     * 也不该留下一条毫无信息量的流水。</p>
     */
    @Test
    void movesTaskBetweenGroupsWithoutTouchingOtherFields() throws Exception {
        long id = insertTask("报销发票", "todo", tomorrow(), "P1", 1, 0, 2, "work");
        String beforeUpdated = jdbcTemplate.queryForObject("SELECT updated_at FROM task WHERE id=?", String.class, id);

        mockMvc.perform(post("/api/v1/tasks/{id}/group", id).session(session)
                        .contentType("application/json").content("{\"grp\":\"life\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.grp").value("life"))
                // 其余字段一个都不能被顺手改掉
                .andExpect(jsonPath("$.data.title").value("报销发票"))
                .andExpect(jsonPath("$.data.priority").value("P1"))
                .andExpect(jsonPath("$.data.due").value(tomorrow()))
                .andExpect(jsonPath("$.data.deep").value(true))
                .andExpect(jsonPath("$.data.postponed").value(2));

        assertThat(jdbcTemplate.queryForObject("SELECT grp FROM task WHERE id=?", String.class, id)).isEqualTo("life");
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM activity_log WHERE log_type='task' AND content LIKE '%报销发票%移到生活%'",
                Integer.class)).isEqualTo(1);

        // 幂等：再切到同一侧不算错，也不重复记流水
        mockMvc.perform(post("/api/v1/tasks/{id}/group", id).session(session)
                        .contentType("application/json").content("{\"grp\":\"life\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.grp").value("life"));
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM activity_log WHERE log_type='task' AND content LIKE '%报销发票%移到生活%'",
                Integer.class)).isEqualTo(1);

        // 非法取值：400 + 中文原因
        mockMvc.perform(post("/api/v1/tasks/{id}/group", id).session(session)
                        .contentType("application/json").content("{\"grp\":\"family\"}"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value(1002));
    }

    /**
     * 编辑任务时可以顺手改分组，且**不改分组时不能把原分组冲掉**。
     *
     * <p>第二条是这次最容易踩的坑：{@code TaskUpdateRequest.grp} 是可空的「不改这一项」语义，
     * 若实现里写成「空值 = 重新判定」或「空值 = 落回 work」，
     * 那么用户只改一个标题就会把一条生活任务悄悄挪到工作里 —— 而且界面上什么都看不出来。</p>
     */
    @Test
    void updatesGroupOnlyWhenExplicitlyProvided() throws Exception {
        long lifeId = insertTask("缴物业费", "todo", null, "P2", 0, 0, 0, "life");
        long workId = insertTask("写方案", "todo", null, "P2", 0, 0, 0, "work");

        // 只改标题：分组必须原样留着
        mockMvc.perform(patch("/api/v1/tasks/{id}", lifeId).session(session)
                        .contentType("application/json").content("{\"title\":\"缴物业费和水电\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.grp").value("life"));

        // 显式改分组：生效
        mockMvc.perform(patch("/api/v1/tasks/{id}", workId).session(session)
                        .contentType("application/json").content("{\"grp\":\"life\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.grp").value("life"));
    }

    private long insertTask(String title, String status, String due, String priority, int deep, int blocking, int postponed) {
        // 默认 work：绝大多数既有用例关心的是状态机 / 排序，不是分组，让它们少写一个参数。
        return insertTask(title, status, due, priority, deep, blocking, postponed, "work");
    }

    private long insertTask(String title, String status, String due, String priority, int deep, int blocking,
                            int postponed, String grp) {
        jdbcTemplate.update("INSERT INTO task(title,priority,status,due_date,is_deep_work,is_blocking,postponed,grp,created_at,updated_at,is_demo) VALUES (?,?,?,?,?,?,?,?,?,?,0)",
                title, priority, status, due, deep, blocking, postponed, grp, nowText(), nowText());
        return jdbcTemplate.queryForObject("SELECT id FROM task WHERE title=?", Long.class, title).longValue();
    }
}
