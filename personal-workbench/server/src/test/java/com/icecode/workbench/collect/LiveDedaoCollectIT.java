package com.icecode.workbench.collect;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * 真实链路的健康探测：<b>真连得到 + 走完整产品代码</b>，但写到临时目录，不碰用户 Vault。
 *
 * <p>命名以 {@code IT} 结尾，因此不会被 {@code mvn test} 默认扫到（surefire 只收 {@code *Test}），
 * 需要显式跑：
 * <pre>mvn -B test -Dtest=LiveDedaoCollectIT</pre>
 *
 * <p>它存在的意义是回答一个 {@link ArticleCollectTest}（用替身）回答不了的问题：
 * 得到改版之后，线上那套 HTML 结构还认不认得出。回归时先跑它。
 */
@SpringBootTest
class LiveDedaoCollectIT {

    /** 只保留 packetId，不带 uid / trace —— 既够用，也不把个人信息写进仓库。 */
    private static final String LIVE_URL =
            "https://www.dedao.cn/share/packet?packetId=4D1oe7kEzR3PpX9FVlV8SeQN8mydaOBQ";

    private static final String TEST_ROOT =
            "target/test-workbench/live-" + UUID.randomUUID().toString();

    @TempDir
    Path vaultDir;

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("workbench.data-dir", () -> TEST_ROOT + "/data");
        registry.add("workbench.home-dir", () -> TEST_ROOT);
    }

    @Autowired private ArticleCollectService articleCollectService;
    @Autowired private JdbcTemplate jdbcTemplate;

    @Test
    void fetchesRealDedaoShareLinkAndWritesNote() {
        jdbcTemplate.update("UPDATE app_config SET config_value='true' WHERE config_key='app.initialized'");
        jdbcTemplate.update("UPDATE app_config SET config_value='Asia/Shanghai' WHERE config_key='app.timezone'");
        jdbcTemplate.update("UPDATE app_config SET config_value=? WHERE config_key='obsidian.vault_path'",
                vaultDir.toAbsolutePath().toString());

        CollectResultVO result = articleCollectService.collect(LIVE_URL);

        assertThat(result.syncStatus).isEqualTo("synced");
        assertThat(result.platform).isEqualTo("得到");
        assertThat(result.title).isNotBlank();
        assertThat(result.collection).contains("·");

        Path note = vaultDir.resolve(result.vaultPath);
        assertThat(Files.exists(note)).isTrue();
        String markdown;
        try {
            markdown = new String(Files.readAllBytes(note), StandardCharsets.UTF_8);
        } catch (Exception exception) {
            throw new AssertionError(exception);
        }
        // 真实抓取的关键判据：正文被还原出来了，而且不是空壳
        assertThat(markdown).contains("[原文链接](https://www.dedao.cn/share/packet?packetId=");
        assertThat(markdown).contains("tags:");
        assertThat(markdown).doesNotContain("$_IGET_USER_NAME_$");
        assertThat(markdown.length()).isGreaterThan(500);

        System.out.println("=== 真实链路验证结果 ===");
        System.out.println("目录: " + result.vaultPath);
        System.out.println("标题: " + result.title);
        System.out.println("课程: " + result.collection);
        System.out.println("作者: " + result.author);
        System.out.println("正文长度: " + markdown.length() + " 字符");
        System.out.println("正文开头: " + markdown.substring(0, Math.min(300, markdown.length())));
    }
}
