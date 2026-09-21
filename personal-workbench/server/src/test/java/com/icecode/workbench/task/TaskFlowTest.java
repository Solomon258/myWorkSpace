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
        // 附件要在 task 之前清：复制那条用例整批搬附件，残留会让「副本有几个附件」读到上一条用例的行。
        jdbcTemplate.update("DELETE FROM attachment");
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
     * <p>判定复用 {@code FavoriteService.autoGroup}（工作关键词命中判 work，否则 life），
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
     * {@code POST /{id}/group}：一键切换、幂等、且只动分组这一格。
     *
     * <p>幂等那条是有意写的：分组是「点一下切到另一侧」，用户点到已经在的那一侧不该报错，
     * 也不该留下一条毫无信息量的流水。</p>
     *
     * <p>⚠️ 2026-09-17 起**前端已经没有这个入口了**：任务页的分组切换器、卡片上的分组徽标、
     * 编辑表单里的分组下拉全部移除（用户要求），{@code api.js} 里的 changeTaskGroup 也标成了
     * 「前端不使用」。这个接口本身**保留不动**（任务的 grp 字段还在、后端仍按关键词自动判定），
     * 所以这条用例继续有意义 —— 别因为它「界面上点不到」就把接口或用例删掉。</p>
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

    /**
     * 两个排序口径（2026-09-20 用户要求「任务菜单的所有任务应该提供两个排序，按截止时间（默认）、
     * 按最后修改时间」）。
     *
     * <p>夹具刻意让「截止时间」与「最后修改时间」的先后**互为相反**：早截止的那条很久没动过、
     * 晚截止的那条刚改过。若两条顺序恰好一致，那么「排序整段没生效」时测试照样全绿 ——
     * 那种断言没有区分力（本项目把「不可能变红的断言」看得比没有断言更糟）。</p>
     */
    @Test
    void sortsByDeadlineByDefaultAndByLastModifiedOnDemand() throws Exception {
        long earlyDue = insertTask("早截止但很久没改", "todo", "2026-01-05", "P2", 0, 0, 0);
        long lateDue = insertTask("晚截止但刚改过", "todo", "2026-12-25", "P2", 0, 0, 0);
        long legacy = insertTask("历史遗留没有修改时间", "todo", null, "P2", 0, 0, 0);
        jdbcTemplate.update("UPDATE task SET updated_at=? WHERE id=?", "2026-01-01 09:00:00", earlyDue);
        jdbcTemplate.update("UPDATE task SET updated_at=? WHERE id=?", "2026-02-02 09:00:00", lateDue);
        // updated_at 在 V1 里是可空列：老数据用 created_at 兜底，不许被静默踢到列表最后。
        jdbcTemplate.update("UPDATE task SET updated_at=NULL, created_at=? WHERE id=?", "2026-03-03 09:00:00", legacy);

        // 不传 sort = 按截止时间（默认）。无截止日期的那条排在最后，与既有口径一致。
        mockMvc.perform(get("/api/v1/tasks").session(session))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].title").value("早截止但很久没改"))
                .andExpect(jsonPath("$.data[1].title").value("晚截止但刚改过"))
                .andExpect(jsonPath("$.data[2].title").value("历史遗留没有修改时间"));
        // 显式写 due 与默认完全一致（默认档不是「另一种排序」）
        mockMvc.perform(get("/api/v1/tasks?sort=due").session(session))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].title").value("早截止但很久没改"));

        // 按最后修改时间：刚改过的在最前，且 created_at 兜底的历史数据排在第二，
        // 而不是因为 updated_at 为 NULL 沉到最后。
        mockMvc.perform(get("/api/v1/tasks?sort=updated").session(session))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].title").value("历史遗留没有修改时间"))
                .andExpect(jsonPath("$.data[1].title").value("晚截止但刚改过"))
                .andExpect(jsonPath("$.data[2].title").value("早截止但很久没改"));

        // 非法取值必须报错并给出合法取值，**不能悄悄回退成默认排序** ——
        // 那样用户以为切过去了，实际看到的还是旧顺序，属于静默失效。
        mockMvc.perform(get("/api/v1/tasks?sort=update").session(session))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(1002))
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("按最后修改时间")));
    }

    /**
     * 复制任务（2026-09-21）：新建一条同名任务、截止日期改为今天、状态重置为「待办」。
     *
     * <p>夹具刻意让源任务偏离「复制品的期望值」：状态是 done、截止是昨天、顺延过 3 次、
     * 分组是 life。这样「哪一项没跟着规则走」都会被断言直接指出来 ——
     * 若源任务本身就长得像复制品（todo + 今天截止 + 顺延 0），那么整条复制逻辑被删掉时
     * 断言照样全绿，那种断言没有区分力。</p>
     *
     * <p>「原任务一个字段都不动」是这一组的另一半：复制品错了最多是烦，源任务被改了是丢数据。
     * 复制与顺延的区别就在这里（顺延改源任务、复制不改），所以两边都得断。</p>
     */
    @Test
    void duplicatesTaskIntoTodayTodoKeepingSourceIntact() throws Exception {
        long id = insertTask("给领导发汇报邮件", "done", yesterday(), "P1", 1, 1, 3, "life");
        jdbcTemplate.update("UPDATE task SET description=?, note=? WHERE id=?", "每天 9 点前发出", "数据从报表里取", id);

        mockMvc.perform(post("/api/v1/tasks/{id}/duplicate", id).session(session))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.title").value("给领导发汇报邮件"))
                .andExpect(jsonPath("$.data.status").value("todo"))
                .andExpect(jsonPath("$.data.due").value(TimeUtil.format(LocalDate.now())))
                .andExpect(jsonPath("$.data.priority").value("P1"))
                .andExpect(jsonPath("$.data.deep").value(true))
                .andExpect(jsonPath("$.data.blocking").value(true))
                .andExpect(jsonPath("$.data.note").value("数据从报表里取"))
                .andExpect(jsonPath("$.data.grp").value("life"))
                .andExpect(jsonPath("$.data.postponed").value(0));

        Long copyId = jdbcTemplate.queryForObject(
                "SELECT id FROM task WHERE title='给领导发汇报邮件' AND id<>?", Long.class, id);
        assertThat(copyId).isNotNull();
        // 顺延次数归零是 SQL 默认值给的（insert 不写这一列）：昨天顺延过 3 次是昨天的事，
        // 记在复制品头上会让它一出生就顶着「顺延≥3次」的红标签。
        assertThat(jdbcTemplate.queryForObject("SELECT postponed FROM task WHERE id=?", Integer.class, copyId)).isEqualTo(0);
        // 收录来源不带：复制品的来源是「另一条任务」，沿用会让「这条收录生成了哪些任务」多算一条。
        assertThat(jdbcTemplate.queryForObject("SELECT source_inbox_id FROM task WHERE id=?", Long.class, copyId)).isNull();
        // 描述也要跟着走（它不在卡片上显示，只在编辑弹层里 —— 漏了肉眼看不见）。
        assertThat(jdbcTemplate.queryForObject("SELECT description FROM task WHERE id=?", String.class, copyId))
                .isEqualTo("每天 9 点前发出");

        // 源任务必须原封不动：状态、日期、顺延次数、标题全都不许被这次复制碰到。
        assertThat(jdbcTemplate.queryForObject("SELECT status FROM task WHERE id=?", String.class, id)).isEqualTo("done");
        assertThat(jdbcTemplate.queryForObject("SELECT due_date FROM task WHERE id=?", String.class, id)).isEqualTo(yesterday());
        assertThat(jdbcTemplate.queryForObject("SELECT postponed FROM task WHERE id=?", Integer.class, id)).isEqualTo(3);
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM task WHERE deleted=0", Integer.class)).isEqualTo(2);

        // 时间线要留一条「复制」流水，且写的是复制不是新建（否则用户翻时间线会以为凭空多了一条任务）。
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM activity_log WHERE content LIKE '复制任务%给领导发汇报邮件%'", Integer.class)).isEqualTo(1);
    }

    /**
     * 源任务没有截止日期时，复制品也填今天（2026-09-21 用户拍板的规则：一律今天）。
     *
     * <p>为什么单开一条：另一种同样合理的设计是「原本没截止就保持没截止」。
     * 这条断言把选定的那个口径钉住，避免以后有人按另一种直觉改回去。</p>
     *
     * <p>顺带验「已取消」的任务也能复制 —— 用户取消掉一条之后反悔、
     * 想重新排一条同样的，不该被逼着先「恢复」再复制。</p>
     */
    @Test
    void duplicateFillsTodayEvenWhenSourceHasNoDueDate() throws Exception {
        long noDueId = insertTask("时间待定的那件事", "todo", null, "P2", 0, 0, 0);
        mockMvc.perform(post("/api/v1/tasks/{id}/duplicate", noDueId).session(session))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.due").value(TimeUtil.format(LocalDate.now())))
                .andExpect(jsonPath("$.data.status").value("todo"));
        // 源任务仍然没有截止日期（没被顺手补上）
        assertThat(jdbcTemplate.queryForObject("SELECT due_date FROM task WHERE id=?", String.class, noDueId)).isNull();

        long canceledId = insertTask("已取消的任务", "canceled", yesterday(), "P3", 0, 0, 0);
        mockMvc.perform(post("/api/v1/tasks/{id}/duplicate", canceledId).session(session))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("todo"))
                .andExpect(jsonPath("$.data.priority").value("P3"));

        // 找不到的任务：404 + 1001（拿 RESOURCE_NOT_FOUND，而不是冒充 400 参数错）。
        mockMvc.perform(post("/api/v1/tasks/{id}/duplicate", 999999L).session(session))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value(1001));
    }

    /**
     * 附件整批复制过去，但**磁盘上只有一份文件**（2026-09-21）。
     *
     * <p>这里守的是两件事，缺一条这个功能就会静默出问题：</p>
     * <ol>
     *   <li><b>附件确实跟过去了</b>。不带的话用户每天还要手动重挂一次，
     *       而界面上只是「缩略图少了几个」，不会报错。</li>
     *   <li><b>两条记录共用同一个 file_name，且删掉源任务之后副本的附件照样打得开</b>。
     *       共用文件之所以安全，是因为 {@code AttachmentService.delete} 只软删数据库行、
     *       物理回收要等引用计数归零。谁要把「删记录顺手删文件」加回去，这条会立刻变红。</li>
     * </ol>
     */
    @Test
    void duplicatesAttachmentsAsSeparateRowsSharingOneFile() throws Exception {
        long id = insertTask("带附件的汇报", "done", yesterday(), "P2", 0, 0, 0);
        // 直接插一行附件：上传接口要真的传文件，这里关心的是「复制」那一步。
        jdbcTemplate.update("INSERT INTO attachment(owner_type,owner_id,file_name,original_name,mime_type,"
                        + "byte_size,width,height,sha256,sort_order,created_at,updated_at,deleted,is_demo)"
                        + " VALUES ('task',?,?,?,?,?,?,?,?,?,?,?,0,0)",
                id, "0123456789abcdef.png", "汇报模板.png", "image/png", 2048L, 120, 80,
                "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef", 0, nowText(), nowText());

        mockMvc.perform(post("/api/v1/tasks/{id}/duplicate", id).session(session))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.attachments.length()").value(1))
                .andExpect(jsonPath("$.data.attachments[0].name").value("汇报模板.png"));

        Long copyId = jdbcTemplate.queryForObject(
                "SELECT id FROM task WHERE title='带附件的汇报' AND id<>?", Long.class, id);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM attachment WHERE owner_type='task' AND owner_id=? AND deleted=0",
                Integer.class, copyId)).isEqualTo(1);
        // 共用一个物理文件：file_name 是 sha256 前 16 位，同内容天然同名。
        assertThat(jdbcTemplate.queryForObject(
                "SELECT file_name FROM attachment WHERE owner_id=?", String.class, copyId)).isEqualTo("0123456789abcdef.png");
        // 源任务的附件原样还在（是复制，不是转绑）。
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM attachment WHERE owner_type='task' AND owner_id=? AND deleted=0",
                Integer.class, id)).isEqualTo(1);

        // 删掉源任务：副本的附件必须还在（两条记录各占一行，谁也不牵连谁）。
        mockMvc.perform(delete("/api/v1/tasks/{id}", id).session(session)).andExpect(status().isOk());
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM attachment WHERE owner_type='task' AND owner_id=? AND deleted=0",
                Integer.class, copyId)).isEqualTo(1);
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
