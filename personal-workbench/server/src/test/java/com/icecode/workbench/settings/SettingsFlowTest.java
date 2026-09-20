package com.icecode.workbench.settings;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
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
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import com.icecode.workbench.auth.AuthConstants;

@SpringBootTest
@AutoConfigureMockMvc
class SettingsFlowTest {

    private static final String TEST_ROOT = "target/test-workbench/settings-" + UUID.randomUUID().toString();

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
        jdbcTemplate.update("UPDATE app_config SET config_value='true' WHERE config_key='app.initialized'");
        jdbcTemplate.update("UPDATE app_config SET config_value='tester' WHERE config_key='app.username'");
        jdbcTemplate.update("UPDATE app_config SET config_value='Asia/Shanghai' WHERE config_key='app.timezone'");
        jdbcTemplate.update("UPDATE app_config SET config_value='' WHERE config_key='ai.enabled'");
        jdbcTemplate.update("DELETE FROM app_config WHERE config_key IN ('ai.enabled','ai.base_url','ai.model','ai.api_key','obsidian.vault_path')");
        session = new MockHttpSession();
        session.setAttribute(AuthConstants.SESSION_USER, "tester");
    }

    @Test
    void readsAndUpdatesProfile() throws Exception {
        mockMvc.perform(get("/api/v1/settings").session(session))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.username").value("tester"))
                .andExpect(jsonPath("$.data.aiEnabled").value(false))
                .andExpect(jsonPath("$.data.aiApiKeySet").value(false));

        mockMvc.perform(put("/api/v1/settings/profile").session(session)
                        .contentType("application/json").content("{\"username\":\"新名字\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.username").value("新名字"));
        assertThat(session.getAttribute(AuthConstants.SESSION_USER)).isEqualTo("新名字");

        mockMvc.perform(put("/api/v1/settings/profile").session(session)
                        .contentType("application/json").content("{}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(1002));
    }

    @Test
    void changesPasswordWithCurrentPasswordCheck() throws Exception {
        String oldHash = new BCryptPasswordEncoder().encode("oldpass123");
        jdbcTemplate.update("UPDATE app_config SET config_value=? WHERE config_key='app.password_hash'", oldHash);

        mockMvc.perform(put("/api/v1/settings/profile").session(session)
                        .contentType("application/json")
                        .content("{\"currentPassword\":\"wrong-pass\",\"newPassword\":\"newpass456\"}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value(2003));

        mockMvc.perform(put("/api/v1/settings/profile").session(session)
                        .contentType("application/json")
                        .content("{\"currentPassword\":\"oldpass123\",\"newPassword\":\"abc\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(1002));

        mockMvc.perform(put("/api/v1/settings/profile").session(session)
                        .contentType("application/json")
                        .content("{\"currentPassword\":\"oldpass123\",\"newPassword\":\"newpass456\"}"))
                .andExpect(status().isOk());
        String hash = jdbcTemplate.queryForObject(
                "SELECT config_value FROM app_config WHERE config_key='app.password_hash'", String.class);
        assertThat(new BCryptPasswordEncoder().matches("newpass456", hash)).isTrue();
    }

    @Test
    void savesAiSettingsWithMaskedKey() throws Exception {
        mockMvc.perform(put("/api/v1/settings/ai").session(session)
                        .contentType("application/json")
                        .content("{\"enabled\":true,\"baseUrl\":\"\",\"model\":\"m\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(1002));

        mockMvc.perform(put("/api/v1/settings/ai").session(session)
                        .contentType("application/json")
                        .content("{\"enabled\":true,\"baseUrl\":\"https://api.example.com/v1\",\"model\":\"demo-model\",\"apiKey\":\"sk-1234567890abcdef\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.aiEnabled").value(true))
                .andExpect(jsonPath("$.data.aiApiKeySet").value(true))
                .andExpect(jsonPath("$.data.aiApiKeyMasked").value("sk-1****cdef"));

        String storedKey = jdbcTemplate.queryForObject(
                "SELECT config_value FROM app_config WHERE config_key='ai.api_key'", String.class);
        assertThat(storedKey).isEqualTo("sk-1234567890abcdef");

        // apiKey 留空时保持原值不变
        mockMvc.perform(put("/api/v1/settings/ai").session(session)
                        .contentType("application/json")
                        .content("{\"enabled\":false,\"baseUrl\":\"https://api.example.com/v1\",\"model\":\"demo-model\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.aiApiKeyMasked").value("sk-1****cdef"));
    }

    @Test
    void savesObsidianVaultPath() throws Exception {
        java.nio.file.Path realVault = java.nio.file.Files.createTempDirectory("wb-vault");
        mockMvc.perform(put("/api/v1/settings/obsidian").session(session)
                        .contentType("application/json").content("{\"vaultPath\":\"" + realVault + "\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.obsidianVaultPath").value(realVault.toString()));
    }

    @Test
    void rejectsNonExistentObsidianVaultPath() throws Exception {
        // 保存即校验路径是否为真实目录：避免「填了路径却仍提示未配置」的静默失败
        // 用系统临时目录下一个确定不存在的子路径，避免依赖具体盘符
        String nonExistent = java.nio.file.Paths.get(System.getProperty("java.io.tmpdir"),
                "wb-vault-missing-" + java.util.UUID.randomUUID() + "-xyz").toString().replace("\\", "\\\\");
        mockMvc.perform(put("/api/v1/settings/obsidian").session(session)
                        .contentType("application/json").content("{\"vaultPath\":\"" + nonExistent + "\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(1002))
                // 关键：BizException 携带的具体原因必须透传到前端，
                // 否则用户只会看到 ErrorCode 的笼统文案「请求参数不正确」，不知道错在哪
                .andExpect(jsonPath("$.message").value(
                        org.hamcrest.Matchers.containsString("应用进程看不到这个目录")));
    }

    @Test
    void rejectsWindowsPathWithContainerHint() throws Exception {
        // 在 Docker 容器里填 Windows 盘符路径是最常见的误用，提示必须给出容器挂载路径的指引
        mockMvc.perform(put("/api/v1/settings/obsidian").session(session)
                        .contentType("application/json")
                        .content("{\"vaultPath\":\"G:\\\\1_Flow\\\\1_Obsidian\\\\data\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(1002))
                .andExpect(jsonPath("$.message").value(
                        org.hamcrest.Matchers.containsString("Docker")));
    }

    @Test
    void rejectsTooLongVaultPathWithFieldMessage() throws Exception {
        // Bean Validation 失败走 MethodArgumentNotValidException 分支，
        // 同样必须透传 @Size(message=...) 的具体原因，而不是「请求参数不正确」
        StringBuilder tooLong = new StringBuilder("/tmp/");
        while (tooLong.length() <= 300) tooLong.append('a');
        mockMvc.perform(put("/api/v1/settings/obsidian").session(session)
                        .contentType("application/json")
                        .content("{\"vaultPath\":\"" + tooLong + "\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(1002))
                .andExpect(jsonPath("$.message").value(
                        org.hamcrest.Matchers.containsString("300")));
    }
}
