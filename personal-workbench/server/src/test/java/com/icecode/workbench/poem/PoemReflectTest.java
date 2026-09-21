package com.icecode.workbench.poem;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import com.icecode.workbench.auth.AuthConstants;
import com.icecode.workbench.knowledge.LlmAnswerClient;

/**
 * 诗词品读接口 {@code POST /api/v1/poems/reflect} 的端到端测试。
 *
 * <p><b>这个测试是补一个真实的漏</b>：2026-09-20 这个接口第一次上线时**没有任何后端测试**，
 * 结果服务是「老进程 + 新类文件」跑着 —— Spring 启动时类还不存在，映射从未注册，
 * 用户点「全屏展示诗词」拿到的是 {@code HTTP 404 /api/v1/poems/reflect 不存在}。
 * 而那时前端与静态检查全绿：静态检查只扫 JS 里有没有人调 {@code WorkbenchApi.reflectPoem}，
 * 它看不见「后端到底注册没注册这个路径」。**只有一条真的走过 DispatcherServlet 的请求能证明这件事。**
 *
 * <p>所以这里的第一条断言刻意就是「路径存在」：任何把 {@code @RequestMapping} 写错、
 * 或把 Controller 挪出扫描范围、或忘了编译的改动，都会让它变红。
 *
 * <p>LLM 用替身（{@link LlmAnswerClient} 是具体类，用 {@code @MockBean} 换掉），
 * 测试不碰网络、不烧 token。
 */
@SpringBootTest
@AutoConfigureMockMvc
class PoemReflectTest {

    private static final String TEST_ROOT =
            "target/test-workbench/poem-" + UUID.randomUUID().toString();
    private static final String PATH = "/api/v1/poems/reflect";
    private static final String TITLE = "天净沙·秋思";
    private static final String BODY = "枯藤老树昏鸦，小桥流水人家，古道西风瘦马。\n夕阳西下，断肠人在天涯。";

    private static final String IMAGERY = "「枯藤老树昏鸦」，六个字里有三样东西在往下沉。";
    private static final String AFFINITY = "你也许刚走过一段没什么人同行的路。慢一点也没关系。";

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("workbench.data-dir", () -> TEST_ROOT + "/data");
        registry.add("workbench.home-dir", () -> TEST_ROOT);
    }

    @Autowired private MockMvc mockMvc;
    @Autowired private JdbcTemplate jdbcTemplate;
    @MockBean private LlmAnswerClient llmClient;
    private MockHttpSession session;

    @BeforeEach
    void setUp() {
        // 每次跑都清掉缓存，否则第二条用例会命中第一条写进去的缓存。
        jdbcTemplate.update("DELETE FROM app_config WHERE config_key LIKE 'poem.reflect.v1.%'");
        jdbcTemplate.update("UPDATE app_config SET config_value='true' WHERE config_key='app.initialized'");
        jdbcTemplate.update("UPDATE app_config SET config_value='Asia/Shanghai' WHERE config_key='app.timezone'");
        when(llmClient.isEnabled()).thenReturn(true);
        session = new MockHttpSession();
        session.setAttribute(AuthConstants.SESSION_USER, "tester");
    }

    private String payload(String title, String body) {
        return "{\"title\":\"" + title + "\",\"dynasty\":\"元\",\"author\":\"马致远\",\"body\":\""
                + body.replace("\n", "\\n") + "\"}";
    }

    /**
     * 这条是「路径真的注册了」的守门断言。
     *
     * <p>它跑得过 = DispatcherServlet 找到了 handler；跑不过（404）= 映射没注册
     * —— 正是用户那次报的错。哪怕 AI 没配、握手失败，也**绝不该**是 404。
     */
    @Test
    void 接口路径已注册_不会返回404() throws Exception {
        when(llmClient.chat(anyString(), anyString(), anyDouble()))
                .thenReturn("{\"imagery\":\"" + IMAGERY + "\",\"affinity\":\"" + AFFINITY + "\"}");

        int code = mockMvc.perform(post(PATH).session(session)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(payload(TITLE, BODY)))
                .andReturn().getResponse().getStatus();

        assertThat(code)
                .as("404 = 映射没注册（用户报过的那个错）；401/403 = 鉴权没走对")
                .isNotEqualTo(404);
        assertThat(code).isEqualTo(200);
    }

    @Test
    void 正常返回两段文字() throws Exception {
        when(llmClient.chat(anyString(), anyString(), anyDouble()))
                .thenReturn("{\"imagery\":\"" + IMAGERY + "\",\"affinity\":\"" + AFFINITY + "\"}");

        mockMvc.perform(post(PATH).session(session)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(payload(TITLE, BODY)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.imagery").value(IMAGERY))
                .andExpect(jsonPath("$.data.affinity").value(AFFINITY))
                .andExpect(jsonPath("$.data.cached").value(false));
    }

    @Test
    void 同一首诗第二次命中缓存_不再调AI() throws Exception {
        when(llmClient.chat(anyString(), anyString(), anyDouble()))
                .thenReturn("{\"imagery\":\"" + IMAGERY + "\",\"affinity\":\"" + AFFINITY + "\"}");

        mockMvc.perform(post(PATH).session(session)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(payload(TITLE, BODY)))
                .andExpect(status().isOk());

        mockMvc.perform(post(PATH).session(session)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(payload(TITLE, BODY)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.cached").value(true))
                .andExpect(jsonPath("$.data.imagery").value(IMAGERY));

        // 只有第一次真打了模型 —— 「加了缓存」这条需求的唯一硬证据。
        verify(llmClient, times(1)).chat(anyString(), anyString(), anyDouble());
    }

    @Test
    void 模型套了markdown代码块也能解析() throws Exception {
        when(llmClient.chat(anyString(), anyString(), anyDouble()))
                .thenReturn("```json\n{\"imagery\":\"" + IMAGERY + "\",\"affinity\":\"" + AFFINITY + "\"}\n```");

        mockMvc.perform(post(PATH).session(session)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(payload(TITLE, BODY)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.imagery").value(IMAGERY));
    }

    @Test
    void 模型只给一段时判失败_不缓存() throws Exception {
        when(llmClient.chat(anyString(), anyString(), anyDouble()))
                .thenReturn("{\"imagery\":\"" + IMAGERY + "\"}");

        // ⚠️ BizException 除少数几个码之外一律映射成 HTTP 400（见 GlobalExceptionHandler.handleBizException）。
        mockMvc.perform(post(PATH).session(session)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(payload(TITLE, BODY)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(1003));

        // 失败结果不能被缓存，否则「再试一次」永远拿到同一句错误。
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM app_config WHERE config_key LIKE 'poem.reflect.v1.%'", Integer.class);
        assertThat(count).as("失败的品读不该进缓存").isZero();
    }

    @Test
    void AI未配置时给出可操作的提示() throws Exception {
        when(llmClient.isEnabled()).thenReturn(false);

        mockMvc.perform(post(PATH).session(session)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(payload(TITLE, BODY)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(1003))
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("设置")));
    }

    @Test
    void 缺少标题或正文时拒绝() throws Exception {
        // 参数校验失败的项目约定：HTTP 400 + code 1002（INVALID_PARAMETER）。
        mockMvc.perform(post(PATH).session(session)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":\"\",\"body\":\"\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(1002));
    }

    @Test
    void 未登录时拦下() throws Exception {
        mockMvc.perform(post(PATH)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(payload(TITLE, BODY)))
                .andExpect(status().isUnauthorized());
    }
}
