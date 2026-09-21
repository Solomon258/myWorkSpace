package com.icecode.workbench.vision;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.UUID;

import javax.imageio.ImageIO;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import com.icecode.workbench.attachment.AttachmentService;
import com.icecode.workbench.common.BizException;
import com.icecode.workbench.common.ErrorCode;
import com.icecode.workbench.inbox.InboxParseWriter;
import com.icecode.workbench.settings.SettingsService;

/**
 * 图片解析的四个可独立验证的部分。
 *
 * <p>真实视觉模型不在这套测试里（不能联网、也不能把用户数据发出去），所以按
 * 「能被证明的部分」切开测：</p>
 * <ol>
 *   <li>降采样：真实图片进、尺寸与格式出，且超长边必须被压到 1600 以内；</li>
 *   <li>配置闸门：三种「配不全」的情形必须在**触发前**就报出可操作的错误；</li>
 *   <li>「仅本地」开关：开了之后绝不允许调用外部模型；</li>
 *   <li>落库形态：一条图片 → 多条待确认，且 raw 被裁到 4000 以内并留痕。</li>
 * </ol>
 *
 * <p>第 4 条是最要紧的：它是「三个既有冲突」里两个的回归守卫
 * （raw_content 4000 字上限、一条收录只能落一条实体）。</p>
 */
@SpringBootTest
class VisionFlowTest {

    private static final String TEST_ROOT = "target/test-workbench/vision-" + UUID.randomUUID().toString();

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("workbench.data-dir", () -> TEST_ROOT + "/data");
        registry.add("workbench.home-dir", () -> TEST_ROOT);
    }

    @Autowired private ImageDownscaler downscaler;
    @Autowired private VisionProvider visionProvider;
    @Autowired private VisionParseService visionParseService;
    @Autowired private InboxParseWriter parseWriter;
    @Autowired private AttachmentService attachmentService;
    @Autowired private JdbcTemplate jdbcTemplate;

    private Path workspace;

    @BeforeEach
    void setUp() throws Exception {
        jdbcTemplate.update("UPDATE app_config SET config_value='true' WHERE config_key='app.initialized'");
        jdbcTemplate.update("UPDATE app_config SET config_value='Asia/Shanghai' WHERE config_key='app.timezone'");
        // 每个用例都从「没配视觉模型」开始，由用例自己打开需要的开关。
        jdbcTemplate.update("UPDATE app_config SET config_value='false' WHERE config_key IN (?, ?)",
                SettingsService.KEY_AI_ENABLED, SettingsService.KEY_AI_VISION_LOCAL_ONLY);
        jdbcTemplate.update("UPDATE app_config SET config_value='' WHERE config_key IN (?, ?, ?)",
                SettingsService.KEY_AI_BASE_URL, SettingsService.KEY_AI_API_KEY,
                SettingsService.KEY_AI_VISION_MODEL);
        workspace = Files.createDirectories(Paths.get(TEST_ROOT));
    }

    // ------------------------------------------------------------------ 降采样

    /**
     * 长边超过 1600 的图必须被压下来。
     *
     * <p>不压的后果不是「慢」，而是**请求被服务端直接拒掉**：10MB 的截图 base64 后
     * 约 13MB，超过多数视觉模型的单请求上限。而错误信息通常只说「请求过大」，
     * 用户完全不知道是哪张图的问题。</p>
     */
    @Test
    void scalesDownLargeImagesToTheConfiguredLongEdge() throws Exception {
        Path path = writePng("big.png", 3200, 2000);

        byte[] jpeg = downscaler.toJpegBytes(path);

        assertThat(jpeg).isNotNull();
        BufferedImage scaled = ImageIO.read(new java.io.ByteArrayInputStream(jpeg));
        assertThat(Math.max(scaled.getWidth(), scaled.getHeight())).isEqualTo(ImageDownscaler.MAX_EDGE);
        // 等比：3200×2000 → 1600×1000，长宽比不能被改（改了文字会变形，识别率掉下来）
        assertThat(scaled.getWidth()).isEqualTo(1600);
        assertThat(scaled.getHeight()).isEqualTo(1000);
        // 必须是 JPEG：GIF / WEBP 有些模型不收，统一转格式是最省心的做法
        assertThat(jpeg[0] & 0xFF).isEqualTo(0xFF);
        assertThat(jpeg[1] & 0xFF).isEqualTo(0xD8);
    }

    /** 本来就小于上限的图不该被放大 —— 放大只会让体积变大而信息量不变。 */
    @Test
    void leavesSmallImagesAtTheirOriginalSize() throws Exception {
        Path path = writePng("small.png", 200, 120);

        byte[] jpeg = downscaler.toJpegBytes(path);

        BufferedImage scaled = ImageIO.read(new java.io.ByteArrayInputStream(jpeg));
        assertThat(scaled.getWidth()).isEqualTo(200);
        assertThat(scaled.getHeight()).isEqualTo(120);
    }

    /** 读不出来的文件返回 null（由调用方回退到原字节），不能抛异常把整批解析打断。 */
    @Test
    void returnsNullForUndecodableContent() throws Exception {
        Path path = workspace.resolve("not-an-image.png");
        Files.write(path, "这其实是一段文本".getBytes(StandardCharsets.UTF_8));

        assertThat(downscaler.toJpegBytes(path)).isNull();
        // 但原字节是能读出来的 —— 这是调用方的兜底路径
        assertThat(downscaler.readRaw(path)).isNotEmpty();
    }

    // ------------------------------------------------------------------ 配置闸门

    /**
     * 三种「配不全」都必须在触发那一刻就报错，而且错误码统一是 3015。
     *
     * <p>为什么强调「触发那一刻」：解析是异步的，扔进队列之后再发现问题只会变成
     * 日志里的一行，用户点完「解析」看到的是一片安静 —— 这正是本项目最忌讳的静默失效。</p>
     */
    @Test
    void refusesToStartParsingWhenVisionIsNotFullyConfigured() {
        // ① 什么都没配
        assertThat(visionProvider.isConfigured()).isFalse();
        assertThatThrownBy(() -> visionParseService.assertParseable(Arrays.asList(Long.valueOf(1L))))
                .isInstanceOf(BizException.class)
                .hasFieldOrPropertyWithValue("errorCode", ErrorCode.VISION_UNSUPPORTED);

        // ② 总开关开着、地址与 key 都有，但没填视觉模型名
        //   这恰恰是最容易漏的一种：同一个 base_url 下文本模型与视觉模型通常不同名
        enableAi();
        assertThat(visionProvider.isConfigured()).isFalse();
        assertThatThrownBy(() -> visionParseService.assertParseable(Arrays.asList(Long.valueOf(1L))))
                .isInstanceOf(BizException.class)
                .hasFieldOrPropertyWithValue("errorCode", ErrorCode.VISION_UNSUPPORTED);

        // ③ 填了视觉模型名 -> 才放行
        setConfig(SettingsService.KEY_AI_VISION_MODEL, "gpt-4o-mini");
        assertThat(visionProvider.isConfigured()).isTrue();
        visionParseService.assertParseable(Arrays.asList(Long.valueOf(1L)));

        // ④ 空列表：不是「配置问题」，是「你还没选图」，报的必须是参数错误
        assertThatThrownBy(() -> visionParseService.assertParseable(java.util.Collections.<Long>emptyList()))
                .isInstanceOf(BizException.class)
                .hasFieldOrPropertyWithValue("errorCode", ErrorCode.INVALID_PARAMETER);
    }

    /**
     * 3015 的文案必须指出**到底缺哪一项**，不能一律说「请填写视觉模型」。
     *
     * <p>这条是用户实报问题后补的（2026-09-16）：他的真实情况是**总开关没开**
     * （{@code ai.enabled=false}），但旧文案只说「请在设置里填写视觉模型」——
     * 他照着填了模型名，回来发现还是不行，因为病根根本不在那儿。
     * 报错的用处就在于告诉用户下一步做什么，指错了地方等于没说。</p>
     */
    @Test
    void missingConfigMessagePointsAtTheActualMissingItem() {
        // ① 总开关没开（其余齐备）→ 必须指向「总开关」，不能让人去填模型名
        setConfig(SettingsService.KEY_AI_BASE_URL, "https://api.deepseek.com");
        setConfig(SettingsService.KEY_AI_API_KEY, "sk-test");
        setConfig(SettingsService.KEY_AI_VISION_MODEL, "deepseek-flash");
        assertThat(visionProvider.isConfigured()).isFalse();
        assertThatThrownBy(() -> visionParseService.assertParseable(Arrays.asList(Long.valueOf(1L))))
                .isInstanceOf(BizException.class)
                .hasFieldOrPropertyWithValue("errorCode", ErrorCode.VISION_UNSUPPORTED)
                .hasMessageContaining("总开关")
                .hasMessageContaining("设置");

        // ② 开了总开关、有 key、但没填 base_url → 指向地址
        setConfig(SettingsService.KEY_AI_ENABLED, "true");
        setConfig(SettingsService.KEY_AI_BASE_URL, "");
        assertThat(visionProvider.describeMissingConfig()).contains("地址");

        // ③ 有地址、没 key → 指向 API Key
        setConfig(SettingsService.KEY_AI_BASE_URL, "https://api.deepseek.com");
        setConfig(SettingsService.KEY_AI_API_KEY, "");
        assertThat(visionProvider.describeMissingConfig()).contains("API Key");

        // ④ 都齐了、只缺视觉模型名 → 这时才该提示填模型名，且要点出 DeepSeek 的真实模型名
        setConfig(SettingsService.KEY_AI_API_KEY, "sk-test");
        setConfig(SettingsService.KEY_AI_VISION_MODEL, "");
        String reason = visionProvider.describeMissingConfig();
        assertThat(reason).contains("视觉模型");
        // 产品名 != API 模型名，这个坑必须写进提示里（实测 deepseek-V4.1-Flash 会被服务端 400 拒掉）
        assertThat(reason).contains("deepseek-flash");

        // ⑤ 全部齐备 → 没有缺失项，返回 null
        setConfig(SettingsService.KEY_AI_VISION_MODEL, "deepseek-flash");
        assertThat(visionProvider.describeMissingConfig()).isNull();
    }

    /**
     * 「仅本地解析」开启后，解析入口必须直接拒掉，且错误文案要告诉用户去哪儿关掉它。
     *
     * <p>这条是隐私闸门：用户开它就是为了不让截图离开本机。如果这里只是「不生效」
     * 而仍然允许点，那张工资条就已经发出去了 —— 没有任何提示能补救。</p>
     */
    @Test
    void localOnlySwitchBlocksCloudParsingWithActionableMessage() {
        enableAi();
        setConfig(SettingsService.KEY_AI_VISION_MODEL, "gpt-4o-mini");
        setConfig(SettingsService.KEY_AI_VISION_LOCAL_ONLY, "true");

        assertThatThrownBy(() -> visionParseService.assertParseable(Arrays.asList(Long.valueOf(1L))))
                .isInstanceOf(BizException.class)
                .hasFieldOrPropertyWithValue("errorCode", ErrorCode.VISION_UNSUPPORTED)
                .hasMessageContaining("仅本地")
                .hasMessageContaining("设置");
    }

    /**
     * 发出去的请求体必须是**多模态数组格式**，且关闭「思考模式」。
     *
     * <p>用本地假 HTTP 服务捕获真实请求体来验，全程不联网。</p>
     *
     * <p>为什么这两条都值得守：</p>
     * <ul>
     *   <li><b>content 必须是数组</b>：写成字符串时服务端会当成纯文本，
     *       <b>图片被静默忽略</b>，模型回一句「我没看到图片」—— 不报错，只是功能没了。</li>
     *   <li><b>thinking 必须 disabled</b>：新一代模型默认开思考，实测同一张微信截图
     *       开思考 8.2s/1648 token、关思考 2.1s/209 token，识别结果一致。
     *       解析是单线程串行的，删掉这个字段会让用户等待时长直接翻四倍。</li>
     * </ul>
     */
    @Test
    void sendsMultimodalArrayBodyWithThinkingDisabled() throws Exception {
        java.util.concurrent.atomic.AtomicReference<String> captured =
                new java.util.concurrent.atomic.AtomicReference<String>();
        com.sun.net.httpserver.HttpServer server =
                com.sun.net.httpserver.HttpServer.create(new java.net.InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/chat/completions", exchange -> {
            captured.set(new String(readAll(exchange.getRequestBody()),
                    java.nio.charset.StandardCharsets.UTF_8));
            byte[] out = ("{\"choices\":[{\"message\":{\"content\":\"{\\\"items\\\":[]}\"}}],"
                    + "\"usage\":{\"prompt_tokens\":1,\"completion_tokens\":1}}")
                    .getBytes(java.nio.charset.StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, out.length);
            exchange.getResponseBody().write(out);
            exchange.close();
        });
        server.start();
        try {
            int port = server.getAddress().getPort();
            enableAi();
            setConfig(SettingsService.KEY_AI_BASE_URL, "http://127.0.0.1:" + port);
            setConfig(SettingsService.KEY_AI_VISION_MODEL, "deepseek-flash");

            Path image = writePng("request-body.png", 320, 200);
            try {
                visionProvider.parse(image, "Asia/Shanghai");
            } catch (BizException ignored) {
                // 这条测的是「发出去的请求长什么样」，响应能不能解析成 items 无关紧要
            }

            String body = captured.get();
            assertThat(body).as("必须真的发起了请求").isNotNull();
            // 关思考：删掉会让单张图从 ~2s 退回 ~8s（实测数据）
            assertThat(body).contains("\"thinking\"");
            assertThat(body).contains("\"disabled\"");
            // content 必须是数组：写成字符串会让图片被静默忽略
            assertThat(body).contains("\"type\":\"image_url\"");
            assertThat(body).contains("data:image/jpeg;base64,");
            // 模型名必须原样透传
            assertThat(body).contains("deepseek-flash");
        } finally {
            server.stop(0);
        }
    }

    private static byte[] readAll(java.io.InputStream in) throws java.io.IOException {
        java.io.ByteArrayOutputStream buffer = new java.io.ByteArrayOutputStream();
        byte[] chunk = new byte[8192];
        int read;
        while ((read = in.read(chunk)) > 0) {
            buffer.write(chunk, 0, read);
        }
        return buffer.toByteArray();
    }

    /**
     * 安全兜底：连源图都找不到时，Provider 也不能去发请求。
     *
     * <p>这条守的是「先校验再提交」的顺序 —— 如果实现改成先建 ai_job 再检查配置，
     * 就会留下一堆永远处于 pending 的流水记录。</p>
     */
    @Test
    void refusesToParseWhenAttachmentIsNotAnImage() {
        long id = attachmentService
                .store(textFile("说明.txt"), null, null).getId();

        assertThatThrownBy(() -> attachmentService.openImage(id))
                .isInstanceOf(BizException.class)
                .hasFieldOrPropertyWithValue("errorCode", ErrorCode.ATTACHMENT_INVALID)
                .hasMessageContaining("不是图片");
    }

    // ------------------------------------------------------------------ 落库形态

    /**
     * 一条图片 → 多条待确认，且第一条**复用占位记录**。
     *
     * <p>复用是刻意的：否则解析完成后页面上会多出一个「正在解析…」的死块，
     * 而它不属于 pending / processed / archived 任何一种状态、也没有入口能删掉。</p>
     */
    @Test
    void expandsOneImageIntoMultiplePendingItemsReusingThePlaceholder() {
        String now = "2026-09-16 10:30:00";
        long placeholderId = parseWriter.begin("正在解析图片「会议通知.png」…", "web", Long.valueOf(7L), now);

        // 解析前：pending + running，用户在收集箱里能看到它正在干活
        assertThat(column(placeholderId, "status")).isEqualTo("pending");
        assertThat(column(placeholderId, "parse_status")).isEqualTo("running");
        assertThat(column(placeholderId, "origin")).isEqualTo("image");

        java.util.List<InboxParseWriter.ParsedItem> items = Arrays.asList(
                new InboxParseWriter.ParsedItem("会议通知全文…", "task", 0.9, "{\"title\":\"确认参会\"}"),
                new InboxParseWriter.ParsedItem("周三 14:00 评审", "schedule", 0.85, "{\"title\":\"评审\"}"),
                new InboxParseWriter.ParsedItem("记得带笔记本", "favorite", 0.6, "{\"title\":\"带笔记本\"}"));

        java.util.List<Long> ids = parseWriter.finish(placeholderId, "web", Long.valueOf(7L), now, false, items);

        assertThat(ids).hasSize(3);
        // 第一条就是原来那个占位 id —— 没有凭空多出来的块
        assertThat(ids.get(0).longValue()).isEqualTo(placeholderId);
        assertThat(column(placeholderId, "status")).isEqualTo("processed");
        assertThat(column(placeholderId, "parse_status")).isEqualTo("success");
        assertThat(column(placeholderId, "ai_category")).isEqualTo("task");
        // 其余两条各自新建，且都指向同一张源图，前端据此把它们显示成一组
        for (Long id : ids) {
            assertThat(numberColumn(id.longValue(), "source_attachment_id")).isEqualTo(7L);
            assertThat(column(id.longValue(), "origin")).isEqualTo("image");
            assertThat(column(id.longValue(), "status")).isEqualTo("processed");
        }
        assertThat(column(ids.get(1).longValue(), "ai_category")).isEqualTo("schedule");
        assertThat(column(ids.get(2).longValue(), "ai_category")).isEqualTo("favorite");

        // 这里不断言时间线：finish() 只负责落条目，汇总流水由 VisionParseService 在
        // 一图多条时**只写一条**（三条流水会把时间线刷屏）。这条规则由
        // VisionParseServiceTest 覆盖，写在这里会变成「测了但没测到东西」。
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM activity_log WHERE content LIKE '%图片解析完成%'", Integer.class))
                .isEqualTo(0);
    }

    /**
     * 原文超过 4000 字必须被裁，并且**留痕**。
     *
     * <p>不裁的后果很隐蔽：写进去的时候没人拦（这条路径绕过了 DTO 校验），
     * 但之后用户在整理页改一个字再保存，后端 DTO 的 {@code @Size(max=4000)} 会报
     * 「收录内容不能超过 4000 字」—— 一个他从没动过的东西突然报参数错误，
     * 完全无从下手。留痕是为了让用户知道「不是模型漏读了，是我们截的」。</p>
     */
    @Test
    void truncatesOverlongRawTextAndLeavesATrace() {
        String now = "2026-09-16 10:30:00";
        long placeholderId = parseWriter.begin("正在解析…", "web", Long.valueOf(9L), now);

        StringBuilder longText = new StringBuilder();
        for (int i = 0; i < 6000; i++) {
            longText.append('图');
        }
        java.util.List<InboxParseWriter.ParsedItem> items = java.util.Collections.singletonList(
                new InboxParseWriter.ParsedItem(
                        longText.substring(0, VisionParseService.MAX_RAW_LENGTH),
                        "favorite", 0.7, "{\"title\":\"长文\"}"));

        parseWriter.finish(placeholderId, "web", Long.valueOf(9L), now, true, items);

        String stored = column(placeholderId, "raw_content").toString();
        assertThat(stored).hasSize(VisionParseService.MAX_RAW_LENGTH);
        assertThat(numberColumn(placeholderId, "raw_truncated")).isEqualTo(1L);
    }

    /** 没被截断时不打标记 —— 否则「已截断」这句提示会天天出现，用户就再也不看它了。 */
    @Test
    void doesNotFlagTruncationWhenNothingWasCut() {
        String now = "2026-09-16 10:30:00";
        long placeholderId = parseWriter.begin("正在解析…", "web", Long.valueOf(9L), now);

        parseWriter.finish(placeholderId, "web", Long.valueOf(9L), now, false,
                java.util.Collections.singletonList(new InboxParseWriter.ParsedItem(
                        "短内容", "favorite", 0.7, "{\"title\":\"短\"}")));

        assertThat(numberColumn(placeholderId, "raw_truncated")).isEqualTo(0L);
    }

    /**
     * 解析失败时条目降级成「待整理」，而不是留在 running 或直接消失。
     *
     * <p>它必须仍然在收集箱里可见：那张图确实传上来了，用户要么手动补文字、
     * 要么删掉它。悄悄丢掉等于骗他「什么都没发生」，而磁盘上还留着那个文件。</p>
     */
    @Test
    void failedParseLeavesAVisiblePendingItemWithTheReason() {
        String now = "2026-09-16 10:30:00";
        long placeholderId = parseWriter.begin("正在解析图片「模糊截图.png」…", "web", Long.valueOf(11L), now);

        parseWriter.fail(placeholderId, "这张图里没读出任何内容，换一张更清晰的试试", now);

        assertThat(column(placeholderId, "status")).isEqualTo("pending");
        assertThat(column(placeholderId, "parse_status")).isEqualTo("failed");
        assertThat(column(placeholderId, "parse_error")).asString()
                .contains("换一张更清晰的");
    }

    // ------------------------------------------------------------------ 辅助

    private void enableAi() {
        setConfig(SettingsService.KEY_AI_ENABLED, "true");
        setConfig(SettingsService.KEY_AI_BASE_URL, "https://example.invalid/v1");
        setConfig(SettingsService.KEY_AI_API_KEY, "sk-test-not-a-real-key");
    }

    private void setConfig(String key, String value) {
        int updated = jdbcTemplate.update("UPDATE app_config SET config_value=? WHERE config_key=?", value, key);
        if (updated == 0) {
            jdbcTemplate.update("INSERT INTO app_config(config_key, config_value) VALUES (?,?)", key, value);
        }
    }

    /**
     * 读一列出来做断言。
     *
     * <p>用「第一个值」而不是按列名取：SQLite 的 JDBC 驱动把列标签原样返回，
     * 但 {@code queryForMap} 的键大小写取决于 SQL 里怎么写的 —— 按名字取会踩到
     * 「列名对上了但键是 'STATUS'」这种低级的空指针，而失败信息完全指不出原因。</p>
     */
    private Object column(long id, String name) {
        java.util.Map<String, Object> row = jdbcTemplate.queryForMap(
                "SELECT " + name + " FROM inbox_item WHERE id=?", Long.valueOf(id));
        return row.values().iterator().next();
    }

    /** 数值列统一按 long 比：SQLite 的 INTEGER 读出来在 Integer / Long 之间摇摆。 */
    private long numberColumn(long id, String name) {
        Object value = column(id, name);
        return value == null ? -1L : ((Number) value).longValue();
    }

    private Path writePng(String name, int width, int height) throws Exception {
        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        Graphics2D graphics = image.createGraphics();
        try {
            graphics.setColor(Color.WHITE);
            graphics.fillRect(0, 0, width, height);
            graphics.setColor(Color.BLACK);
            graphics.drawString("测试图片 " + width + "x" + height, 10, 20);
        } finally {
            graphics.dispose();
        }
        Path path = workspace.resolve(name);
        ImageIO.write(image, "png", path.toFile());
        return path;
    }

    private org.springframework.mock.web.MockMultipartFile textFile(String name) {
        return new org.springframework.mock.web.MockMultipartFile(
                "file", name, "text/plain", "纯文本内容".getBytes(StandardCharsets.UTF_8));
    }
}
