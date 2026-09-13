package com.icecode.workbench.system;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
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
import com.icecode.workbench.util.TimeUtil;

@SpringBootTest
@AutoConfigureMockMvc
class SystemFlowTest {

    private static final String TEST_ROOT = "target/test-workbench/system-" + UUID.randomUUID().toString();
    private static final Path BACKUPS = Paths.get(TEST_ROOT, "data", "backups");

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("workbench.data-dir", () -> TEST_ROOT + "/data");
        registry.add("workbench.home-dir", () -> TEST_ROOT);
    }

    @Autowired private MockMvc mockMvc;
    @Autowired private JdbcTemplate jdbcTemplate;
    private MockHttpSession session;

    @BeforeEach
    void setUp() throws Exception {
        jdbcTemplate.update("DELETE FROM pomodoro");
        jdbcTemplate.update("DELETE FROM activity_log");
        jdbcTemplate.update("DELETE FROM inbox_item");
        jdbcTemplate.update("DELETE FROM memo");
        jdbcTemplate.update("DELETE FROM schedule_event");
        jdbcTemplate.update("DELETE FROM task");
        jdbcTemplate.update("DELETE FROM ai_job");
        jdbcTemplate.update("UPDATE app_config SET config_value='true' WHERE config_key='app.initialized'");
        jdbcTemplate.update("UPDATE app_config SET config_value='Asia/Shanghai' WHERE config_key='app.timezone'");
        jdbcTemplate.update("DELETE FROM app_config WHERE config_key IN ('ai.enabled','ai.base_url','ai.model','ai.api_key')");
        if (Files.isDirectory(BACKUPS)) {
            try (java.util.stream.Stream<Path> stream = Files.list(BACKUPS)) {
                for (Path file : stream.toArray(Path[]::new)) Files.deleteIfExists(file);
            }
        }
        session = new MockHttpSession();
        session.setAttribute(AuthConstants.SESSION_USER, "tester");
    }

    @Test
    void manualBackupProducesConsistentSnapshot() throws Exception {
        jdbcTemplate.update("INSERT INTO task(title, priority, status, created_at) VALUES ('备份验证任务','P2','todo',?)",
                TimeUtil.now("Asia/Shanghai"));

        MvcResult result = mockMvc.perform(post("/api/v1/system/backup").session(session)
                        .contentType("application/json").content("{}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.file").value(org.hamcrest.Matchers.startsWith("workbench-")))
                .andReturn();
        String body = result.getResponse().getContentAsString();
        String fileName = body.replaceAll(".*\"file\":\"([^\"]+)\".*", "$1");
        Path backupFile = BACKUPS.resolve(fileName);
        assertThat(Files.isRegularFile(backupFile)).isTrue();
        assertThat(Files.size(backupFile)).isGreaterThan(0L);

        // 备份库与运行库行数一致
        try (Connection connection = DriverManager.getConnection("jdbc:sqlite:" + backupFile.toString().replace('\\', '/'));
             Statement statement = connection.createStatement();
             ResultSet rs = statement.executeQuery("SELECT COUNT(*) FROM task WHERE title='备份验证任务'")) {
            assertThat(rs.next()).isTrue();
            assertThat(rs.getInt(1)).isEqualTo(1);
        }

        mockMvc.perform(get("/api/v1/system/backups").session(session))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].name").value(fileName));
    }

    @Test
    void rejectsTargetPathOutsideBackupsDir() throws Exception {
        mockMvc.perform(post("/api/v1/system/backup").session(session)
                        .contentType("application/json").content("{\"targetPath\":\"../escape.db\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(1002));
    }

    @Test
    void restoresBackupAndRollsBackNewerData() throws Exception {
        jdbcTemplate.update("INSERT INTO task(title, priority, status, created_at) VALUES ('恢复前任务','P2','todo',?)",
                TimeUtil.now("Asia/Shanghai"));
        MvcResult backupResult = mockMvc.perform(post("/api/v1/system/backup").session(session)
                        .contentType("application/json").content("{}"))
                .andExpect(status().isOk()).andReturn();
        String fileName = backupResult.getResponse().getContentAsString()
                .replaceAll(".*\"file\":\"([^\"]+)\".*", "$1");

        jdbcTemplate.update("INSERT INTO task(title, priority, status, created_at) VALUES ('恢复后新增','P2','todo',?)",
                TimeUtil.now("Asia/Shanghai"));
        Integer before = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM task", Integer.class);
        assertThat(before.intValue()).isEqualTo(2);

        mockMvc.perform(post("/api/v1/system/restore").session(session)
                        .contentType("application/json")
                        .content("{\"fileName\":\"" + fileName + "\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(3006));

        // 迁移数量动态取自当前库：每加一个 V*.sql 都要来改一次硬编码数字是个维护陷阱，
        // 这里真正要断言的是「恢复接口如实报告了被恢复库的迁移数」。
        Integer expectedMigrations = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM flyway_schema_history WHERE success = 1", Integer.class);
        mockMvc.perform(post("/api/v1/system/restore").session(session)
                        .contentType("application/json")
                        .content("{\"fileName\":\"" + fileName + "\",\"confirm\":true}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.restoredFrom").value(fileName))
                .andExpect(jsonPath("$.data.preRestoreBackup").value(org.hamcrest.Matchers.startsWith("pre-restore-")))
                .andExpect(jsonPath("$.data.schemaMigrations").value(expectedMigrations))
                .andExpect(jsonPath("$.data.tableCounts.task").value(1));

        Integer after = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM task", Integer.class);
        assertThat(after.intValue()).isEqualTo(1);
    }

    @Test
    void rejectsInvalidRestoreFile() throws Exception {
        Files.createDirectories(BACKUPS);
        Files.write(BACKUPS.resolve("broken.db"), "not a sqlite file".getBytes("UTF-8"));
        mockMvc.perform(post("/api/v1/system/restore").session(session)
                        .contentType("application/json")
                        .content("{\"fileName\":\"broken.db\",\"confirm\":true}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(3005));
        mockMvc.perform(post("/api/v1/system/restore").session(session)
                        .contentType("application/json")
                        .content("{\"fileName\":\"missing.db\",\"confirm\":true}"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value(1001));
    }

    @Test
    void clearsOnlyDemoDataWithConfirm() throws Exception {
        String now = TimeUtil.now("Asia/Shanghai");
        jdbcTemplate.update("INSERT INTO task(title, priority, status, created_at, is_demo) VALUES ('示例任务','P2','todo',?,1)", now);
        jdbcTemplate.update("INSERT INTO task(title, priority, status, created_at, is_demo) VALUES ('真实任务','P2','todo',?,0)", now);
        jdbcTemplate.update("INSERT INTO memo(title, content, grp, status, created_at, is_demo) VALUES ('示例备忘','内容','life','active',?,1)", now);

        mockMvc.perform(post("/api/v1/system/demo/clear").session(session)
                        .contentType("application/json").content("{\"confirm\":false}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(3006));

        mockMvc.perform(post("/api/v1/system/demo/clear").session(session)
                        .contentType("application/json").content("{\"confirm\":true}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.total").value(2));

        Integer tasks = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM task", Integer.class);
        Integer memos = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM memo", Integer.class);
        assertThat(tasks.intValue()).isEqualTo(1);
        assertThat(memos.intValue()).isEqualTo(0);
    }

    @Test
    void clearsDemoDataReferencedByRealRowsWithoutForeignKeyViolation() throws Exception {
        // 复现「清空示例数据」报 500 的真实场景：
        // 用户把一条「示例」收件条目整理成真实任务（source_inbox_id 指向示例 inbox_item），
        // clearDemo 直接 DELETE 示例 inbox_item 会触发外键约束。应先解关联再删。
        String now = TimeUtil.now("Asia/Shanghai");
        jdbcTemplate.update("INSERT INTO inbox_item(raw_content, content_type, source, status, created_at, is_demo) "
                + "VALUES ('示例收件条目','text','wecom','pending',?,1)", now);
        Integer inboxId = jdbcTemplate.queryForObject("SELECT id FROM inbox_item WHERE raw_content='示例收件条目'", Integer.class);
        // 真实任务（is_demo=0）引用该示例收件条目
        jdbcTemplate.update("INSERT INTO task(title, priority, status, source_inbox_id, created_at, is_demo) "
                + "VALUES ('从示例整理出的真实任务','P2','todo',?,?,0)", inboxId, now);

        mockMvc.perform(post("/api/v1/system/demo/clear").session(session)
                        .contentType("application/json").content("{\"confirm\":true}"))
                .andExpect(status().isOk());

        // 示例收件条目被清空，真实任务保留（仅断开 source_inbox_id 关联）
        Integer inboxLeft = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM inbox_item WHERE raw_content='示例收件条目'", Integer.class);
        Integer realTaskLeft = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM task WHERE title='从示例整理出的真实任务'", Integer.class);
        assertThat(inboxLeft.intValue()).isEqualTo(0);
        assertThat(realTaskLeft.intValue()).isEqualTo(1);
        Integer nulledLink = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM task WHERE title='从示例整理出的真实任务' AND source_inbox_id IS NULL", Integer.class);
        assertThat(nulledLink.intValue()).isEqualTo(1);
    }

    @Test
    void llmFailureFallsBackToLocalRules() throws Exception {
        // 配置一个不可达的 LLM 地址，验证超时/异常自动回退本地规则
        jdbcTemplate.update("INSERT INTO app_config(config_key, config_value) VALUES ('ai.enabled','true')");
        jdbcTemplate.update("INSERT INTO app_config(config_key, config_value) VALUES ('ai.base_url','http://127.0.0.1:1')");
        jdbcTemplate.update("INSERT INTO app_config(config_key, config_value) VALUES ('ai.model','unreachable')");
        jdbcTemplate.update("INSERT INTO app_config(config_key, config_value) VALUES ('ai.api_key','sk-fake')");

        mockMvc.perform(post("/api/v1/inbox").session(session)
                        .contentType("application/json").content("{\"raw\":\"明天下午三点开评审会\"}"))
                .andExpect(status().isOk());
        mockMvc.perform(post("/api/v1/inbox/classify").session(session))
                .andExpect(status().isOk());

        String category = jdbcTemplate.queryForObject(
                "SELECT ai_category FROM inbox_item WHERE raw_content='明天下午三点开评审会'", String.class);
        assertThat(category).isEqualTo("schedule");
        String jobStatus = jdbcTemplate.queryForObject(
                "SELECT status FROM ai_job ORDER BY id DESC LIMIT 1", String.class);
        assertThat(jobStatus).isEqualTo("success");
    }
}
