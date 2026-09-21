package com.icecode.workbench.timeline;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
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

@SpringBootTest
@AutoConfigureMockMvc
class TimelineFlowTest {

    private static final String TEST_ROOT = "target/test-workbench/timeline-" + UUID.randomUUID().toString();

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
        jdbcTemplate.update("DELETE FROM activity_log");
        jdbcTemplate.update("UPDATE app_config SET config_value='true' WHERE config_key='app.initialized'");
        jdbcTemplate.update("UPDATE app_config SET config_value='Asia/Shanghai' WHERE config_key='app.timezone'");
        session = new MockHttpSession();
        session.setAttribute(AuthConstants.SESSION_USER, "tester");
    }

    @Test
    void deleteRemovesOnlyTheTargetRecord() throws Exception {
        long first = insertLog("favorite", "work", "清理收集箱");
        long second = insertLog("task", null, "完成限流方案评审");

        mockMvc.perform(delete("/api/v1/activity/{id}", first).session(session))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0));

        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM activity_log WHERE id=?", Integer.class, first)).isEqualTo(0);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM activity_log WHERE id=?", Integer.class, second)).isEqualTo(1);

        mockMvc.perform(get("/api/v1/activity").session(session))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(1))
                .andExpect(jsonPath("$.data[0].id").value((int) second));
    }

    @Test
    void deleteMissingRecordReturnsNotFound() throws Exception {
        mockMvc.perform(delete("/api/v1/activity/{id}", 987654321L).session(session))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value(1001))
                // 具体原因要透传，而不是 ErrorCode 的笼统文案「请求的内容不存在」
                .andExpect(jsonPath("$.message").value(
                        org.hamcrest.Matchers.containsString("不存在")));
    }

    private long insertLog(String type, String category, String content) {
        jdbcTemplate.update(
                "INSERT INTO activity_log(log_type, category, content, created_at, is_demo) VALUES (?,?,?,?,0)",
                type, category, content, "2026-09-09T10:00:00+08:00");
        return jdbcTemplate.queryForObject("SELECT id FROM activity_log ORDER BY id DESC LIMIT 1", Long.class)
                .longValue();
    }
}
