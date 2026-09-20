package com.icecode.workbench.attachment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Base64;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import com.icecode.workbench.auth.AuthConstants;

/**
 * 附件的上传 / 读取 / 绑定 / 删除。
 *
 * <p>重点守三类「写错了也不报错」的地方：类型伪造、路径逃逸、越权绑定。</p>
 */
@SpringBootTest
@AutoConfigureMockMvc
class AttachmentFlowTest {

    private static final String TEST_ROOT = "target/test-workbench/attachment-" + UUID.randomUUID().toString();

    /** 真实 1×1 PNG：魔数正确，且 ImageIO 能读出宽高。 */
    private static final String PNG_BASE64 =
            "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mP8z8DwHwAFBQIAX8jx0gAAAABJRU5ErkJggg==";

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("workbench.data-dir", () -> TEST_ROOT + "/data");
        registry.add("workbench.home-dir", () -> TEST_ROOT);
    }

    @Autowired private MockMvc mockMvc;
    @Autowired private JdbcTemplate jdbcTemplate;
    @Autowired private AttachmentService attachmentService;
    private MockHttpSession session;

    @BeforeEach
    void setUp() {
        jdbcTemplate.update("DELETE FROM attachment");
        jdbcTemplate.update("UPDATE app_config SET config_value='true' WHERE config_key='app.initialized'");
        jdbcTemplate.update("UPDATE app_config SET config_value='Asia/Shanghai' WHERE config_key='app.timezone'");
        session = new MockHttpSession();
        session.setAttribute(AuthConstants.SESSION_USER, "tester");
    }

    // ------------------------------------------------------------------ 上传

    @Test
    void storesImageAndReportsItsSizeAndKind() throws Exception {
        MvcResult result = mockMvc.perform(multipart("/api/v1/attachments")
                        .file(pngFile("聊天记录.png")).session(session))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.image").value(true))
                .andExpect(jsonPath("$.data.mimeType").value("image/png"))
                .andExpect(jsonPath("$.data.name").value("聊天记录.png"))
                .andExpect(jsonPath("$.data.url").value(org.hamcrest.Matchers.containsString("/api/v1/attachments/")))
                .andReturn();

        long id = readLong(result, "$.data.id");
        // 宽高必须真的读出来（前端要用来占位，防止图片加载时页面抖动）
        assertThat(jdbcTemplate.queryForObject("SELECT width FROM attachment WHERE id=?", Integer.class, id))
                .isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject("SELECT height FROM attachment WHERE id=?", Integer.class, id))
                .isEqualTo(1);
    }

    /**
     * 声明是 image/png、字节却不是图片 —— 必须拒。
     * 只查后缀或只信 Content-Type 的实现会把它当图片收下，然后送去视觉模型。
     */
    @Test
    void rejectsFileThatClaimsToBeAnImageButIsNot() throws Exception {
        MockMultipartFile fake = new MockMultipartFile("file", "伪造.png", "image/png",
                "这不是图片，只是一段文本".getBytes(StandardCharsets.UTF_8));

        MvcResult result = mockMvc.perform(multipart("/api/v1/attachments").file(fake).session(session))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(3010))
                .andReturn();

        assertThat(body(result)).contains("不是有效的图片文件");
    }

    @Test
    void rejectsUnsupportedDocumentType() throws Exception {
        MockMultipartFile exe = new MockMultipartFile("file", "evil.exe",
                "application/octet-stream", "MZ".getBytes(StandardCharsets.UTF_8));

        MvcResult result = mockMvc.perform(multipart("/api/v1/attachments").file(exe).session(session))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(3010))
                .andReturn();

        // 报错要能指导操作：说清哪个文件、支持哪些类型
        assertThat(body(result)).contains("evil.exe").contains("不是支持的文件类型");
    }

    @Test
    void acceptsDocumentsThatBrowsersReportAsOctetStream() throws Exception {
        // md / json / csv 这类文件浏览器常不给准确 MIME，按扩展名白名单放行
        MockMultipartFile markdown = new MockMultipartFile("file", "接口约定.md",
                "application/octet-stream", "# 标题".getBytes(StandardCharsets.UTF_8));

        mockMvc.perform(multipart("/api/v1/attachments").file(markdown).session(session))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.image").value(false))
                .andExpect(jsonPath("$.data.name").value("接口约定.md"));
    }

    @Test
    void rejectsImageLargerThanTenMegabytes() throws Exception {
        byte[] huge = new byte[11 * 1024 * 1024];
        System.arraycopy(pngBytes(), 0, huge, 0, 12);   // 魔数正确，只是超大

        MockMultipartFile file = new MockMultipartFile("file", "大图.png", "image/png", huge);

        MvcResult result = mockMvc.perform(multipart("/api/v1/attachments").file(file).session(session))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(3013))
                .andReturn();

        assertThat(body(result)).contains("大图.png").contains("超过");
    }

    @Test
    void rejectsRequestWithoutFile() throws Exception {
        // required=false 是刻意的：漏传文件时要给中文原因，而不是让 Spring 抛成 500
        mockMvc.perform(multipart("/api/v1/attachments").session(session))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(3010));
    }

    // ------------------------------------------------------------------ 落盘与安全

    /**
     * 原文件名里带目录穿越时，落盘名必须**完全不采信它**。
     *
     * <p>落盘名由 sha256 生成，原文件名只入库用于展示 —— 这条断言同时钉住「数据库里的
     * original_name 已去掉目录部分」和「磁盘上不存在越界的文件」。</p>
     */
    @Test
    void neverUsesOriginalFileNameAsPath() throws Exception {
        MockMultipartFile evil = new MockMultipartFile("file", "../../../../etc/passwd.png",
                "image/png", pngBytes());

        MvcResult result = mockMvc.perform(multipart("/api/v1/attachments").file(evil).session(session))
                .andExpect(status().isOk())
                .andReturn();

        long id = readLong(result, "$.data.id");
        String fileName = jdbcTemplate.queryForObject(
                "SELECT file_name FROM attachment WHERE id=?", String.class, id);
        String originalName = jdbcTemplate.queryForObject(
                "SELECT original_name FROM attachment WHERE id=?", String.class, id);

        assertThat(fileName).doesNotContain("/").doesNotContain("..");
        assertThat(fileName).matches("[0-9a-f]{16}\\.png");
        assertThat(originalName).isEqualTo("passwd.png");   // 只留文件名部分
    }

    @Test
    void dedupesIdenticalContentOntoOneFileButKeepsSeparateRecords() throws Exception {
        MvcResult first = mockMvc.perform(multipart("/api/v1/attachments")
                        .file(pngFile("a.png")).session(session))
                .andExpect(status().isOk()).andReturn();
        MvcResult second = mockMvc.perform(multipart("/api/v1/attachments")
                        .file(pngFile("b.png")).session(session))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.deduped").value(true))
                .andReturn();

        long firstId = readLong(first, "$.data.id");
        long secondId = readLong(second, "$.data.id");
        assertThat(secondId).isNotEqualTo(firstId);
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM attachment", Integer.class)).isEqualTo(2);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(DISTINCT file_name) FROM attachment", Integer.class)).isEqualTo(1);
    }

    // ------------------------------------------------------------------ 读取

    @Test
    void servesRawBytesWithImmutableCacheHeaders() throws Exception {
        MvcResult uploaded = mockMvc.perform(multipart("/api/v1/attachments")
                        .file(pngFile("图.png")).session(session))
                .andExpect(status().isOk()).andReturn();
        long id = readLong(uploaded, "$.data.id");

        mockMvc.perform(get("/api/v1/attachments/{id}/raw", id).session(session))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Type", "image/png"))
                .andExpect(header().string("Cache-Control", containsString("immutable")))
                .andExpect(header().string("ETag", containsString("\"")));
    }

    @Test
    void missingAttachmentIdGivesNotFound() throws Exception {
        mockMvc.perform(get("/api/v1/attachments/{id}/raw", 999999).session(session))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value(3011));
    }

    // ------------------------------------------------------------------ 绑定

    @Test
    void bindsUploadedAttachmentToOwner() throws Exception {
        long id = upload(pngFile("图.png"), null, null);

        attachmentService.bindAll(Arrays.asList(Long.valueOf(id)), "task", 7L);

        assertThat(jdbcTemplate.queryForObject(
                "SELECT owner_type FROM attachment WHERE id=?", String.class, id)).isEqualTo("task");
        assertThat(jdbcTemplate.queryForObject(
                "SELECT owner_id FROM attachment WHERE id=?", Long.class, id)).isEqualTo(7L);

        mockMvc.perform(get("/api/v1/attachments").param("ownerType", "task")
                        .param("ownerId", "7").session(session))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(1));
    }

    /**
     * 已绑到别处的附件必须拒绝，而不是悄悄改归属 ——
     * 否则同一个附件会在两个实体下同时出现，用户完全看不出来。
     */
    @Test
    void refusesToStealAttachmentThatBelongsToAnotherOwner() throws Exception {
        long id = upload(pngFile("图.png"), null, null);
        attachmentService.bindAll(Arrays.asList(Long.valueOf(id)), "task", 7L);

        try {
            attachmentService.bindAll(Arrays.asList(Long.valueOf(id)), "memo", 9L);
            org.junit.jupiter.api.Assertions.fail("应当拒绝把已归属的附件改挂到别的记录上");
        } catch (com.icecode.workbench.common.BizException exception) {
            assertThat(exception.getErrorCode()).isEqualTo(com.icecode.workbench.common.ErrorCode.ATTACHMENT_OCCUPIED);
        }
    }

    @Test
    void bindingIsIdempotentForTheSameOwner() throws Exception {
        long id = upload(pngFile("图.png"), null, null);
        attachmentService.bindAll(Arrays.asList(Long.valueOf(id)), "task", 7L);
        // 重复提交同一批 id 不该报错（前端重试、双击提交都会走到这里）
        attachmentService.bindAll(Arrays.asList(Long.valueOf(id)), "task", 7L);
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM attachment", Integer.class)).isEqualTo(1);
    }

    @Test
    void rejectsUnknownAttachmentIdOnBinding() throws Exception {
        try {
            attachmentService.bindAll(Arrays.asList(Long.valueOf(999999)), "task", 7L);
            org.junit.jupiter.api.Assertions.fail("应当报附件不存在");
        } catch (com.icecode.workbench.common.BizException exception) {
            assertThat(exception.getErrorCode()).isEqualTo(com.icecode.workbench.common.ErrorCode.ATTACHMENT_NOT_FOUND);
        }
    }

    // -------------------------------------------------- 转绑 / 列表带附件（2026-09-19）

    /**
     * 收录条目确认生成备忘时，附件必须跟着走。
     *
     * <p>用户报的原始问题是「收录（或网页）加了附件之后，在备忘列表里看不到它」。查下来有**两层**原因，
     * 这一条守的是更底下那层：附件当时压根没转绑 —— 生成的备忘一个附件都没有，
     * 所以只改前端也还是看不到。</p>
     */
    @Test
    void transfersAttachmentsFromInboxItemToTheEntityItBecomes() throws Exception {
        long attachmentId = upload(pngFile("费用截图.png"), null, null);
        long inboxId = createInboxItem("腾讯云费用 -198 元", attachmentId);

        confirmInboxAsMemo(inboxId);

        long memoId = jdbcTemplate.queryForObject(
                "SELECT id FROM memo WHERE source_inbox_id=?", Long.class, Long.valueOf(inboxId)).longValue();
        assertThat(jdbcTemplate.queryForObject(
                "SELECT owner_type FROM attachment WHERE id=?", String.class, Long.valueOf(attachmentId)))
                .isEqualTo("memo");
        assertThat(jdbcTemplate.queryForObject(
                "SELECT owner_id FROM attachment WHERE id=?", Long.class, Long.valueOf(attachmentId)))
                .isEqualTo(memoId);

        // 转绑是「搬走」而不是「再挂一份」：收录条目自己不再持有它。
        // 若不校验这一条，「复制一份」的实现也能让上面两行全绿，而结果是同一个附件
        // 在两个实体下同时出现、删一处另一处还指着同一份盘上文件。
        mockMvc.perform(get("/api/v1/attachments").param("ownerType", "inbox_item")
                        .param("ownerId", String.valueOf(inboxId)).session(session))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(0));

        // 转绑之后「重新解析」仍然要读得到原图：source_attachment_id 不跟着走，
        // 而读取只按 id 查、不校验归属。
        mockMvc.perform(get("/api/v1/attachments/{id}/raw", attachmentId).session(session))
                .andExpect(status().isOk());
    }

    /**
     * 列表接口必须**自己带上**附件，而不是让前端逐条再查一次。
     *
     * <p>一屏 100 条任务按条查就是 100 次查询，而 Hikari 池只有 4 个连接 —— 串行排队会肉眼可见地卡。
     * 所以这一条钉的是「列表路径的返回里就有 attachments」。</p>
     */
    @Test
    void memoListCarriesItsAttachments() throws Exception {
        long attachmentId = upload(pngFile("费用截图.png"), null, null);

        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .post("/api/v1/memos").session(session)
                        .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                        .content("{\"title\":\"独一无二的标题XZQ\",\"content\":\"正文\",\"attachmentIds\":["
                                + attachmentId + "]}"))
                .andExpect(status().isOk());

        mockMvc.perform(get("/api/v1/memos").param("q", "独一无二的标题XZQ").session(session))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(1))
                .andExpect(jsonPath("$.data[0].attachments.length()").value(1))
                .andExpect(jsonPath("$.data[0].attachments[0].name").value("费用截图.png"))
                .andExpect(jsonPath("$.data[0].attachments[0].image").value(true))
                .andExpect(jsonPath("$.data[0].attachments[0].url")
                        .value(org.hamcrest.Matchers.containsString("/raw")));
    }

    /** 没有附件的记录要下发**空数组**而不是 null：前端按「空数组」写最省事，null 会到处漏判。 */
    @Test
    void recordsWithoutAttachmentsCarryAnEmptyArray() throws Exception {
        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .post("/api/v1/memos").session(session)
                        .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                        .content("{\"title\":\"没有附件ZQW\",\"content\":\"正文\"}"))
                .andExpect(status().isOk());

        mockMvc.perform(get("/api/v1/memos").param("q", "没有附件ZQW").session(session))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].attachments.length()").value(0));
    }

    /**
     * 转绑必须**只搬属于来源记录的那些**。
     *
     * <p>repository 层的 UPDATE 带着原归属做二次校验，所以「查完被别处挪走」不会变成抢别人的附件。
     * 这里直接打 service 层：来源记录上没有附件时什么都不该发生。</p>
     */
    @Test
    void transferringFromAnOwnerWithoutAttachmentsIsANoOp() throws Exception {
        long otherId = upload(pngFile("别人的图.png"), "task", 88L);

        int moved = attachmentService.transferOwner("inbox_item", 12345L, "memo", 777L);

        assertThat(moved).isZero();
        assertThat(jdbcTemplate.queryForObject(
                "SELECT owner_type FROM attachment WHERE id=?", String.class, Long.valueOf(otherId)))
                .isEqualTo("task");
        assertThat(jdbcTemplate.queryForObject(
                "SELECT owner_id FROM attachment WHERE id=?", Long.class, Long.valueOf(otherId)))
                .isEqualTo(88L);
    }

    // ------------------------------------------------------------------ 删除

    @Test
    void softDeleteWritesDeletedAtAndHidesFromList() throws Exception {
        long id = upload(pngFile("图.png"), "task", 7L);

        mockMvc.perform(delete("/api/v1/attachments/{id}", id).session(session))
                .andExpect(status().isOk());

        // 软删必须同时写 deleted_at，否则按项目约定（V7）它就不算真正的软删
        assertThat(jdbcTemplate.queryForObject("SELECT deleted FROM attachment WHERE id=?", Integer.class, id))
                .isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject("SELECT deleted_at FROM attachment WHERE id=?", String.class, id))
                .isNotBlank();

        mockMvc.perform(get("/api/v1/attachments").param("ownerType", "task")
                        .param("ownerId", "7").session(session))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(0));
    }

    @Test
    void ownerTypeAndOwnerIdMustBePassedTogether() throws Exception {
        MockMultipartFile file = pngFile("图.png");
        mockMvc.perform(multipart("/api/v1/attachments").file(file)
                        .param("ownerId", "7").session(session))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(1002));

        mockMvc.perform(multipart("/api/v1/attachments").file(pngFile("图.png"))
                        .param("ownerType", "task").session(session))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(1002));
    }

    // ------------------------------------------------------------------ 夹具

    /** 建一条带附件的收录条目（附件此时归属 inbox_item，确认生成实体时应当转绑走）。 */
    private long createInboxItem(String raw, long attachmentId) throws Exception {
        MvcResult result = mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .post("/api/v1/inbox").session(session)
                        .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                        .content("{\"raw\":\"" + raw + "\",\"attachmentIds\":[" + attachmentId + "]}"))
                .andExpect(status().isOk()).andReturn();
        return readLong(result, "$.data.id");
    }

    /**
     * 把收录条目确认成备忘。
     *
     * <p>先把状态置成 {@code processed}：新收录的条目是 {@code pending}，而
     * {@code InboxService.validateConfirm} 只放行 processed / failed。
     * 这里直接改状态而不是跑一次 AI 整理 —— 本测试要验的是**附件转绑**，
     * 让整理器的实现细节（以及它要不要联网）掺进来只会让它变得又慢又脆。</p>
     */
    private void confirmInboxAsMemo(long inboxId) throws Exception {
        jdbcTemplate.update("UPDATE inbox_item SET status='processed' WHERE id=?", Long.valueOf(inboxId));
        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .post("/api/v1/inbox/{id}/confirm", Long.valueOf(inboxId)).session(session)
                        .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                        .content("{\"category\":\"memo\",\"title\":\"腾讯云云服务支付 198 元\"}"))
                .andExpect(status().isOk());
    }

    private MockMultipartFile pngFile(String name) {
        return new MockMultipartFile("file", name, "image/png", pngBytes());
    }

    private static byte[] pngBytes() {
        return Base64.getDecoder().decode(PNG_BASE64);
    }

    private long upload(MockMultipartFile file, String ownerType, Long ownerId) throws Exception {
        org.springframework.test.web.servlet.request.MockMultipartHttpServletRequestBuilder request =
                multipart("/api/v1/attachments").file(file);
        if (ownerType != null) {
            // param() 修改自身并返回 this，这里忽略返回值以免破坏声明类型
            request.param("ownerType", ownerType);
            request.param("ownerId", String.valueOf(ownerId));
        }
        MvcResult result = mockMvc.perform(request.session(session))
                .andExpect(status().isOk()).andReturn();
        return readLong(result, "$.data.id");
    }

    private long readLong(MvcResult result, String path) throws Exception {
        Number value = com.jayway.jsonpath.JsonPath.read(body(result), path);
        return value.longValue();
    }

    /** 必须显式用 UTF-8：默认按 ISO-8859-1 解码会让中文断言假红。 */
    private String body(MvcResult result) throws Exception {
        return result.getResponse().getContentAsString(StandardCharsets.UTF_8);
    }
}
