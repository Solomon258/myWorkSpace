package com.icecode.workbench.auth;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest
@AutoConfigureMockMvc
class AuthValidationTest {

    private static final String TEST_ROOT = "target/test-workbench/auth-validation-" + UUID.randomUUID().toString();

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("workbench.data-dir", () -> TEST_ROOT + "/data");
        registry.add("workbench.home-dir", () -> TEST_ROOT);
    }

    @Autowired
    private MockMvc mockMvc;

    @Test
    void rejectsUnknownTimezone() throws Exception {
        mockMvc.perform(post("/api/v1/auth/init")
                        .contentType("application/json")
                        .content("{\"username\":\"admin\",\"password\":\"Password123\",\"timezone\":\"Foo/Bar\",\"seedDemo\":false}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(2005));
    }

    @Test
    void rejectsPasswordLongerThanSeventyTwoUtf8Bytes() throws Exception {
        StringBuilder password = new StringBuilder();
        for (int i = 0; i < 30; i++) {
            password.append("密");
        }
        String json = "{\"username\":\"admin\",\"password\":\"" + password
                + "\",\"timezone\":\"Asia/Shanghai\",\"seedDemo\":false}";

        mockMvc.perform(post("/api/v1/auth/init")
                        .contentType("application/json")
                        .content(json))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(2006));
    }

    /**
     * 字段校验的提示会原样进前端 toast，所以必须是能指导操作的中文，
     * 不能是 Hibernate 的默认英文（"must not be blank" / "size must be between 0 and 72"）。
     */
    @Test
    void blankCredentialsReturnActionableChineseHint() throws Exception {
        mockMvc.perform(post("/api/v1/auth/login")
                        .contentType("application/json")
                        .content("{\"username\":\"\",\"password\":\"\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(1002))
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("不能为空")))
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.not(
                        org.hamcrest.Matchers.containsString("must not be blank"))));
    }

    @Test
    void overlongUsernameHintStatesTheRealLimit() throws Exception {
        StringBuilder username = new StringBuilder();
        for (int i = 0; i < 40; i++) username.append("a");

        mockMvc.perform(post("/api/v1/auth/login")
                        .contentType("application/json")
                        .content("{\"username\":\"" + username + "\",\"password\":\"12345678\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("用户名不能超过 32 字"));
    }
}
