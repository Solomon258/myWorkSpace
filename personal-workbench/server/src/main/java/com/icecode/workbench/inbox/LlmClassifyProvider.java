package com.icecode.workbench.inbox;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import java.util.regex.Pattern;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.icecode.workbench.auth.AppConfigRepository;
import com.icecode.workbench.settings.SettingsService;
import com.icecode.workbench.util.TextUtil;
import com.icecode.workbench.util.TimeUtil;

/**
 * 可选的外部 LLM 整理器（OpenAI 兼容接口）。仅在设置页开启并配置完整后生效；
 * 任何超时、网络、格式异常都会抛出，由路由层回退到本地规则，绝不改变原始数据。
 * 密钥只出现在请求头，不写入日志与 ai_job。
 */
@Service
public class LlmClassifyProvider implements ClassifyProvider {

    private static final Logger LOGGER = LoggerFactory.getLogger(LlmClassifyProvider.class);
    private static final double AUTO_CONFIRM_THRESHOLD = 0.70;
    /** 与 InboxConfirmRequest.title 的 @Size(max=200) 对齐：兜底标题必须能通过确认接口校验。 */
    private static final int MAX_TITLE_LENGTH = 200;
    private static final Pattern DATE = Pattern.compile("\\d{4}-\\d{2}-\\d{2}");
    private static final Pattern CLOCK = Pattern.compile("([01]\\d|2[0-3]):[0-5]\\d");
    private static final String SYSTEM_PROMPT =
            "你是一个整理助手。把用户的一句话分类为 task（可执行任务）、schedule（有明确时间的日程）、"
            + "favorite（生活/工作提醒与杂项）或 knowledge（值得长期沉淀的知识：笔记、总结、资料、文章、方法论、模板）。"
            + "只输出 JSON，不要输出其他任何文字。格式："
            + "{\"category\":\"task|schedule|favorite|knowledge\",\"confidence\":0到1之间的小数,"
            + "\"payload\":{\"title\":\"不超过24字的短标题\",\"due\":\"yyyy-MM-dd或null\","
            + "\"priority\":\"P0|P1|P2|P3\",\"start\":\"HH:mm或null\",\"end\":\"HH:mm或null\","
            + "\"eventType\":\"meeting|deep_block|other或null\"}}";

    private final AppConfigRepository configRepository;
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final AtomicLong promptTokens = new AtomicLong();
    private final AtomicLong completionTokens = new AtomicLong();

    public LlmClassifyProvider(AppConfigRepository configRepository) {
        this.configRepository = configRepository;
    }

    @Override
    public String name() { return "llm"; }

    public boolean isEnabled() {
        Map<String, String> config = configRepository.findValues(
                SettingsService.KEY_AI_ENABLED, SettingsService.KEY_AI_BASE_URL, SettingsService.KEY_AI_API_KEY);
        return Boolean.parseBoolean(config.get(SettingsService.KEY_AI_ENABLED))
                && notBlank(config.get(SettingsService.KEY_AI_BASE_URL))
                && notBlank(config.get(SettingsService.KEY_AI_API_KEY));
    }

    /** 取出并重置自上次以来的 token 用量（供 ai_job 记录）。 */
    public long[] drainTokens() {
        return new long[]{promptTokens.getAndSet(0), completionTokens.getAndSet(0)};
    }

    @Override
    public ClassifySuggestionVO classify(String raw, String timezone) throws Exception {
        Map<String, String> config = configRepository.findValues(
                SettingsService.KEY_AI_BASE_URL, SettingsService.KEY_AI_MODEL, SettingsService.KEY_AI_API_KEY);
        String baseUrl = config.get(SettingsService.KEY_AI_BASE_URL).trim();
        String model = config.get(SettingsService.KEY_AI_MODEL);
        String apiKey = config.get(SettingsService.KEY_AI_API_KEY);
        if (!notBlank(apiKey)) throw new IllegalStateException("AI 未配置");

        String endpoint = baseUrl.endsWith("/chat/completions") ? baseUrl
                : baseUrl.replaceAll("/+$", "") + "/chat/completions";

        Map<String, Object> body = new HashMap<String, Object>();
        body.put("model", notBlank(model) ? model.trim() : "gpt-3.5-turbo");
        body.put("temperature", Double.valueOf(0));
        Map<String, String> responseFormat = new HashMap<String, String>();
        responseFormat.put("type", "json_object");
        body.put("response_format", responseFormat);
        Map<String, String> system = new HashMap<String, String>();
        system.put("role", "system");
        system.put("content", SYSTEM_PROMPT);
        Map<String, String> user = new HashMap<String, String>();
        user.put("role", "user");
        user.put("content", "时区：" + timezone + "，今天：" + TimeUtil.today(timezone) + "。原始内容：" + raw);
        body.put("messages", new Map[]{system, user});

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set("Authorization", "Bearer " + apiKey.trim());

        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(3000);
        factory.setReadTimeout(10000);
        RestTemplate restTemplate = new RestTemplate(factory);

        String response = restTemplate.postForObject(endpoint, new HttpEntity<Map<String, Object>>(body, headers), String.class);
        return parseResponse(response, raw);
    }

    private ClassifySuggestionVO parseResponse(String response, String raw) throws Exception {
        if (response == null) throw new IllegalStateException("AI 返回为空");
        JsonNode root = objectMapper.readTree(response);
        JsonNode usage = root.path("usage");
        if (usage.isObject()) {
            promptTokens.addAndGet(usage.path("prompt_tokens").asLong(0));
            completionTokens.addAndGet(usage.path("completion_tokens").asLong(0));
        }
        JsonNode choices = root.path("choices");
        if (!choices.isArray() || choices.size() == 0) throw new IllegalStateException("AI 返回缺少 choices");
        String content = choices.get(0).path("message").path("content").asText("");
        content = stripFence(content);
        JsonNode result;
        try {
            result = objectMapper.readTree(content);
        } catch (Exception exception) {
            throw new IllegalStateException("AI 输出不是合法 JSON");
        }
        String category = result.path("category").asText("");
        if (!"task".equals(category) && !"schedule".equals(category) && !"favorite".equals(category)
                && !"knowledge".equals(category)) {
            throw new IllegalStateException("AI 输出类别不合法");
        }
        double confidence = result.path("confidence").isNumber() ? result.path("confidence").asDouble(0.75) : 0.75;
        confidence = Math.max(0, Math.min(1, confidence));

        JsonNode payloadNode = result.path("payload");
        ClassifyPayload payload = new ClassifyPayload();
        String title = payloadNode.path("title").asText("");
        // 兜底标题会被预填进整理页的标题输入框，最终可能直接落库；
        // 不能拼「…」，按 InboxConfirmRequest.title 的上限 200 字裁剪即可。
        payload.setTitle(notBlank(title) ? title.trim() : TextUtil.clip(raw, MAX_TITLE_LENGTH));
        payload.setDue(validOrNull(payloadNode.path("due").asText(null), DATE));
        String priority = payloadNode.path("priority").asText("P2");
        payload.setPriority(priority.matches("P[0-3]") ? priority : "P2");
        payload.setStart(validOrNull(payloadNode.path("start").asText(null), CLOCK));
        payload.setEnd(validOrNull(payloadNode.path("end").asText(null), CLOCK));
        String eventType = payloadNode.path("eventType").asText(null);
        payload.setEventType("meeting".equals(eventType) || "deep_block".equals(eventType) || "other".equals(eventType)
                ? eventType : null);
        return new ClassifySuggestionVO(category, confidence, payload, confidence < AUTO_CONFIRM_THRESHOLD);
    }

    private String stripFence(String content) {
        String text = content == null ? "" : content.trim();
        if (text.startsWith("```")) {
            int firstNewline = text.indexOf('\n');
            if (firstNewline > 0) text = text.substring(firstNewline + 1);
            if (text.endsWith("```")) text = text.substring(0, text.length() - 3);
        }
        return text.trim();
    }

    private String validOrNull(String value, Pattern pattern) {
        if (value == null) return null;
        String trimmed = value.trim();
        return pattern.matcher(trimmed).matches() ? trimmed : null;
    }

    private boolean notBlank(String value) { return value != null && !value.trim().isEmpty(); }
}
