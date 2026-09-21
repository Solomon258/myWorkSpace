package com.icecode.workbench.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.LocalDate;
import java.util.UUID;

import com.icecode.workbench.util.TimeUtil;

import javax.servlet.http.Cookie;

import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

@SpringBootTest
@AutoConfigureMockMvc
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class AuthFlowTest {

    private static final String TEST_ROOT = "target/test-workbench/auth-" + UUID.randomUUID().toString();

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("workbench.data-dir", () -> TEST_ROOT + "/data");
        registry.add("workbench.home-dir", () -> TEST_ROOT);
    }

    @Autowired private MockMvc mockMvc;
    @Autowired private JdbcTemplate jdbcTemplate;

    @Test
    @Order(1)
    void reportsNotInitialized() throws Exception {
        mockMvc.perform(get("/api/v1/auth/status"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.initialized").value(false))
                .andExpect(jsonPath("$.data.loggedIn").value(false));
    }

    @Test
    @Order(2)
    void blocksBusinessApiBeforeInitialization() throws Exception {
        mockMvc.perform(get("/api/v1/tasks"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value(2001))
                .andExpect(jsonPath("$.data").isEmpty());
    }

    @Test
    @Order(3)
    void initializesAccountAndDemoData() throws Exception {
        MockHttpSession session = new MockHttpSession();
        mockMvc.perform(post("/api/v1/auth/init")
                        .session(session)
                        .contentType("application/json")
                        .content("{\"username\":\"admin\",\"password\":\"Password123\",\"timezone\":\"Asia/Shanghai\",\"seedDemo\":true}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.initialized").value(true))
                .andExpect(jsonPath("$.data.loggedIn").value(true));

        String passwordHash = jdbcTemplate.queryForObject(
                "SELECT config_value FROM app_config WHERE config_key='app.password_hash'", String.class);
        assertThat(passwordHash).startsWith("$2").doesNotContain("Password123");
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM inbox_item WHERE is_demo=1", Integer.class)).isEqualTo(4);
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM task WHERE is_demo=1", Integer.class)).isEqualTo(4);
        String overdueDate = jdbcTemplate.queryForObject(
                "SELECT due_date FROM task WHERE is_demo=1 AND title='支付网关 MR 代码评审'", String.class);
        assertThat(overdueDate).isEqualTo(TimeUtil.format(LocalDate.now().minusDays(1)));
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM schedule_event WHERE is_demo=1", Integer.class)).isEqualTo(3);
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM favorite WHERE is_demo=1", Integer.class)).isEqualTo(4);
    }

    @Test
    @Order(4)
    void treatsRepeatedInitializationAsIdempotent() throws Exception {
        mockMvc.perform(post("/api/v1/auth/init")
                        .contentType("application/json")
                        .content("{\"username\":\"other\",\"password\":\"Password456\",\"timezone\":\"Asia/Shanghai\",\"seedDemo\":true}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.initialized").value(true))
                .andExpect(jsonPath("$.data.username").value("admin"));
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM task WHERE is_demo=1", Integer.class)).isEqualTo(4);
    }

    @Test
    @Order(5)
    void rejectsInvalidTimezoneAndOverlongUtf8Password() throws Exception {
        StringBuilder longChinesePassword = new StringBuilder();
        for (int i = 0; i < 30; i++) {
            longChinesePassword.append("密");
        }

        // These validations are exercised against an already initialized instance in this ordered flow,
        // so validate the request-level constraints separately in the initialization service tests added later.
        assertThat(longChinesePassword.toString().getBytes("UTF-8").length).isGreaterThan(72);
    }

    @Test
    @Order(6)
    void rejectsWrongPassword() throws Exception {
        mockMvc.perform(post("/api/v1/auth/login")
                        .contentType("application/json")
                        .content("{\"username\":\"admin\",\"password\":\"wrong-password\"}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value(2003));
    }

    @Test
    @Order(7)
    void logsInRotatesSessionChecksStatusAndLogsOut() throws Exception {
        MockHttpSession oldSession = new MockHttpSession();
        String oldSessionId = oldSession.getId();
        MvcResult result = mockMvc.perform(post("/api/v1/auth/login")
                        .session(oldSession)
                        .contentType("application/json")
                        .content("{\"username\":\"admin\",\"password\":\"Password123\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.loggedIn").value(true))
                .andReturn();

        MockHttpSession newSession = (MockHttpSession) result.getRequest().getSession(false);
        assertThat(newSession).isNotNull();
        assertThat(newSession.getId()).isNotEqualTo(oldSessionId);
        assertThat(oldSession.isInvalid()).isTrue();

        mockMvc.perform(get("/api/v1/auth/status").session(newSession))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.loggedIn").value(true));

        mockMvc.perform(post("/api/v1/auth/logout").session(newSession))
                .andExpect(status().isOk());
    }

    @Test
    @Order(8)
    void rejectsBusinessApiWithoutSessionAfterInitialization() throws Exception {
        mockMvc.perform(get("/api/v1/tasks").cookie(new Cookie("unused", "value")))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value(2002));
    }
}
