package com.icecode.workbench.knowledge;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import com.icecode.workbench.auth.AuthConstants;

/**
 * 存量导入：把 Vault 里早就存在的笔记回填进知识库。
 *
 * <p>重点守两条：<b>重复点不会长出重复条目</b>（幂等），以及<b>不碰用户的文件</b>。
 * 这两条都出过事才可怕 —— 前者会让列表里出现几十条同名笔记，后者会损坏用户的资产。
 */
@SpringBootTest
@AutoConfigureMockMvc
class VaultImportTest {

    private static final String TEST_ROOT =
            "target/test-workbench/vault-import-" + UUID.randomUUID().toString();
    private static final String NOTE_TITLE = "01｜路径：使用逻辑讲道理，需要哪三步？";
    /** 列名白名单：拼 SQL 这种写法，即便拼的是常量也不该留在代码里。 */
    private static final java.util.Set<String> ALLOWED_COLUMNS = new java.util.HashSet<String>(
            java.util.Arrays.asList("content", "tags", "sync_status", "vault_path"));

    @TempDir
    Path vaultDir;

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
        jdbcTemplate.update("DELETE FROM knowledge_note");
        jdbcTemplate.update("DELETE FROM activity_log");
        jdbcTemplate.update("UPDATE app_config SET config_value='true' WHERE config_key='app.initialized'");
        jdbcTemplate.update("UPDATE app_config SET config_value='Asia/Shanghai' WHERE config_key='app.timezone'");
        jdbcTemplate.update("UPDATE app_config SET config_value=? WHERE config_key='obsidian.vault_path'",
                vaultDir.toAbsolutePath().toString());
        session = new MockHttpSession();
        session.setAttribute(AuthConstants.SESSION_USER, "tester");
    }

    @Test
    void importsExistingNotesThenSkipsThemOnSecondRun() throws Exception {
        writeNote("知识体系/得到/吴军·逻辑思维训练50讲/" + NOTE_TITLE + ".md",
                "---\ntags:\n  - 吴军\n  - 得到\n---\n[原文链接](https://www.dedao.cn/share/packet?packetId=a)\n\n第一段正文。\n");
        writeNote("知识体系/得到/吴军·信息论/信息论-脑图.md",
                "---\ntags:\n  - 吴军\n---\n脑图正文内容。\n");
        // 干扰项：不在导入目录里，不该被扫到
        writeNote("00-Inbox/别的笔记.md", "不应被导入。\n");

        importDir("知识体系/得到")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.scanned").value(2))
                .andExpect(jsonPath("$.data.imported").value(2))
                .andExpect(jsonPath("$.data.skipped").value(0));

        // 标题取文件名（用户既有笔记没有 H1）；正文剥掉 frontmatter；标签沿用原 frontmatter
        assertThat(noteField("content")).contains("第一段正文。").doesNotContain("tags:");
        assertThat(noteField("tags")).contains("吴军").contains("得到");
        assertThat(noteField("sync_status")).isEqualTo("synced");
        assertThat(noteField("vault_path"))
                .isEqualTo("知识体系/得到/吴军·逻辑思维训练50讲/" + NOTE_TITLE + ".md");

        // 幂等：再导入一次不该产生新条目
        importDir("知识体系/得到")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.scanned").value(2))
                .andExpect(jsonPath("$.data.imported").value(0))
                .andExpect(jsonPath("$.data.skipped").value(2));

        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM knowledge_note", Integer.class))
                .isEqualTo(2);
    }

    @Test
    void neverModifiesTheVaultFiles() throws Exception {
        String relative = "知识体系/得到/某课/某笔记.md";
        writeNote(relative, "---\ntags:\n  - 某标签\n---\n原始正文，一个字都不该变。\n");
        byte[] before = Files.readAllBytes(vaultDir.resolve(relative));

        importDir("知识体系/得到").andExpect(status().isOk());

        assertThat(Files.readAllBytes(vaultDir.resolve(relative))).isEqualTo(before);
    }

    @Test
    void emptyDirectoryReportsZeroRatherThanFailing() throws Exception {
        importDir("知识体系/得到")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.scanned").value(0))
                .andExpect(jsonPath("$.data.imported").value(0));
    }

    private String noteField(String column) {
        if (!ALLOWED_COLUMNS.contains(column)) {
            throw new IllegalArgumentException("不在白名单里的列：" + column);
        }
        return jdbcTemplate.queryForObject(
                "SELECT " + column + " FROM knowledge_note WHERE title=?", String.class, NOTE_TITLE);
    }

    private void writeNote(String relative, String body) throws Exception {
        Path file = vaultDir.resolve(relative);
        Files.createDirectories(file.getParent());
        Files.write(file, body.getBytes(StandardCharsets.UTF_8));
    }

    private ResultActions importDir(String dir) throws Exception {
        return mockMvc.perform(post("/api/v1/knowledge/import").session(session)
                .contentType("application/json").content("{\"dir\":\"" + dir + "\"}"));
    }
}
