package com.icecode.workbench.search;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.charset.StandardCharsets;
import java.util.UUID;

import org.hamcrest.Matchers;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import com.icecode.workbench.auth.AuthConstants;

/**
 * 语义联想（{@code POST /api/v1/search/expand}）。
 *
 * <p>这一组用例守的是一条产品判断：<b>语义是增强，不是依赖。</b>
 * 未配置 AI、模型超时、返回格式不对，都必须表现为「这次没有联想词」，
 * 而不是让搜索报错 —— 拿主链路的可用性去换一个锦上添花的功能是不划算的。</p>
 *
 * <p>因此这里**不断言"能联想起什么"**（那要真调模型，测试会变慢且不稳定），
 * 只断言「没有 AI 时也必须体面地降级」这条底线，以及入参校验的中文文案。
 * 联想真实生效的路径由 {@code SearchFlowTest} 用显式的 {@code expand} 参数覆盖。</p>
 */
@SpringBootTest
@AutoConfigureMockMvc
class SearchExpandTest {

    private static final String TEST_ROOT = "target/test-workbench/expand-" + UUID.randomUUID().toString();

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
        // 把 AI 配置清空：本测试要验的正是「没配置 AI 时会怎样」。
        // 用 LIKE 而不是逐个列 key，免得以后新增一个 ai.* 配置又漏一个。
        jdbcTemplate.update("DELETE FROM app_config WHERE config_key LIKE 'ai.%'");
        jdbcTemplate.update("UPDATE app_config SET config_value='true' WHERE config_key='app.initialized'");
        session = new MockHttpSession();
        session.setAttribute(AuthConstants.SESSION_USER, "tester");
    }

    /** 没配置 AI 时返回空词表且 **code 仍为 0**：联想失败绝不能把搜索本身拖成错误。 */
    @Test
    void withoutAiReturnsEmptyTermsAndStillSucceeds() throws Exception {
        mockMvc.perform(post("/api/v1/search/expand")
                        .session(session)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"q\":\"限速\"}".getBytes(StandardCharsets.UTF_8)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.terms.length()").value(0))
                .andExpect(jsonPath("$.data.enabled").value(false));
    }

    @Test
    void blankQueryIsRejectedWithChineseReason() throws Exception {
        mockMvc.perform(post("/api/v1/search/expand")
                        .session(session)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"q\":\"  \"}".getBytes(StandardCharsets.UTF_8)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(1002))
                .andExpect(jsonPath("$.message").value(Matchers.containsString("请输入要联想的词")));
    }

    @Test
    void overlongQueryIsRejectedWithLimitInTheMessage() throws Exception {
        StringBuilder longQuery = new StringBuilder();
        for (int i = 0; i < 101; i++) longQuery.append('测');

        mockMvc.perform(post("/api/v1/search/expand")
                        .session(session)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(("{\"q\":\"" + longQuery + "\"}").getBytes(StandardCharsets.UTF_8)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(1002))
                .andExpect(jsonPath("$.message").value(Matchers.containsString("100")));
    }

    @Test
    void requiresLogin() throws Exception {
        mockMvc.perform(post("/api/v1/search/expand")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"q\":\"限速\"}".getBytes(StandardCharsets.UTF_8)))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value(2002));
    }
}
