package com.icecode.workbench.common;

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

import com.icecode.workbench.auth.AuthConstants;

/**
 * 客户端自己写错请求，不能被报成服务端故障。
 *
 * 背景（2026-09-11 实测）：这三种情况原本都落到 `Exception` 兜底分支，
 * 返回 **HTTP 500 + code 1003「系统暂时不可用」**，但如果用户拿这套 API 做自动化，
 * 就会把「我请求写错了」误判成「服务挂了」，进而触发无意义的重试和告警。
 * 正确行为是 4xx + 能指出问题的中文说明。
 */
@SpringBootTest
@AutoConfigureMockMvc
class ClientErrorMappingTest {

    private static final String TEST_ROOT = "target/test-workbench/client-error-" + UUID.randomUUID().toString();

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("workbench.data-dir", () -> TEST_ROOT + "/data");
        registry.add("workbench.home-dir", () -> TEST_ROOT);
    }

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private MockHttpSession session;

    @BeforeEach
    void setUp() {
        // 未映射路径要先过鉴权拦截器，所以必须带「已登录」的会话，
        // 否则测到的是 2002「请先登录」，看不到真实的路由层行为。
        jdbcTemplate.update("UPDATE app_config SET config_value='true' WHERE config_key='app.initialized'");
        jdbcTemplate.update("UPDATE app_config SET config_value='Asia/Shanghai' WHERE config_key='app.timezone'");
        session = new MockHttpSession();
        session.setAttribute(AuthConstants.SESSION_USER, "tester");
    }

    /**
     * 未映射的 `/api/...` 路径（前端 api.js 写错路由、或后端改了路由前端没跟上）。
     *
     * 契约：**404**，不是 500、也不是 2002。已登录时 404 才看得到——未登录会被鉴权拦截器
     * 提前拦成 2002「请先登录」，所以这条用例必须带会话。
     *
     * 注意（2026-09-11 踩到）：这里**只断言状态码，不断言响应体**。MockMvc 下响应体是**空的**，
     * 而真实容器返回的是 Spring 默认错误体 `{timestamp,status,error,path}`（实测确认）。
     * 两者不一致，照 MockMvc 的空响应体去写前端分支会在生产环境完全不触发。
     * 前端 `api.js` 里对「4xx 且响应体没有 code」的处理同时覆盖这两种形态。
     */
    @Test
    void unmappedApiPathIsAPlainNotFoundNotAServerError() throws Exception {
        mockMvc.perform(get("/api/v1/no-such-endpoint").session(session))
                .andExpect(status().isNotFound());
    }

    /**
     * 「路径存在但方法用错」必须仍然是 **405 并列出允许的方法**，不能被任何兜底改成 404。
     *
     * 守卫背景（2026-09-11 踩到）：为了让未映射路径也返回统一响应体，曾加过一个
     * `@RequestMapping("/api/**")` 的兜底控制器。它是**任意方法**匹配，会抢在
     * `HttpRequestMethodNotSupportedException` 之前命中，于是
     * `GET /api/v1/auth/login`（只有 POST）和 `GET /api/v1/events/xyz`（只有 PATCH/DELETE）
     * **全部从 405 退化成 404**。404 只会说「找不到」，405 会告诉你「允许的方法是 [PATCH, DELETE]」，
     * 后者才真正能指导操作——兜底把更有用的错误盖掉了，属于负优化。
     * 结论：不要用 `/api/**` 兜底来统一 404 响应体。
     */
    @Test
    void wrongMethodOnAnExistingPathStays405EvenForPathVariables() throws Exception {
        mockMvc.perform(get("/api/v1/events/xyz").session(session))
                .andExpect(status().isMethodNotAllowed())
                .andExpect(jsonPath("$.code").value(1002))
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("PATCH")));
    }

    @Test
    void malformedJsonBodyIsAClientErrorNotAServerError() throws Exception {
        mockMvc.perform(post("/api/v1/auth/login")
                        .contentType("application/json").content("{bad json"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(1002))
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("JSON")));
    }

    @Test
    void missingBodySaysTheBodyIsMissing() throws Exception {
        mockMvc.perform(post("/api/v1/auth/login").contentType("application/json"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(1002))
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("不能为空")));
    }

    @Test
    void wrongHttpMethodReturns405WithTheOffendingMethod() throws Exception {
        mockMvc.perform(get("/api/v1/auth/login"))
                .andExpect(status().isMethodNotAllowed())
                .andExpect(jsonPath("$.code").value(1002))
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("GET")));
    }
}
