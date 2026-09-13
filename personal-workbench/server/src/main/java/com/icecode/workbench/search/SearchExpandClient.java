package com.icecode.workbench.search;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

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

/**
 * 查询扩展用的 LLM 客户端（把「限速」扩展成「限流 / 速率限制 / 流量控制」）。
 *
 * <p>为什么不复用 {@code knowledge.LlmAnswerClient}：那个客户端的读超时是 20 秒，
 * 而这里必须压在 <b>1200ms</b> —— 搜索是「边打字边看结果」的交互，
 * 等 20 秒不如直接不给联想。两者的失败语义也不同：问答失败要降级成摘录并告知用户，
 * 联想失败要**完全静默**。硬凑一个类会让这两套语义互相污染。</p>
 *
 * <p>本类只管「调模型并把结果解析成词表」，任何异常都直接抛给
 * {@link SearchExpandService} 去降级，不在这里吞。</p>
 */
@Service
public class SearchExpandClient {

    private static final int CONNECT_TIMEOUT_MS = 800;
    private static final int READ_TIMEOUT_MS = 1200;
    private static final int MAX_TERMS = 5;

    /** 模型有时会把 JSON 包在解释文字或 ``` 里，所以先抓出最外层数组再解析。 */
    private static final Pattern ARRAY = Pattern.compile("\\[[\\s\\S]*?\\]");

    private static final String SYSTEM_PROMPT =
            "你是检索助手。用户给一个检索词，你给出 3 到 5 个中文的近义、同义或常见别称说法，"
            + "用于扩大全文检索的召回面。只输出一个 JSON 字符串数组，不要输出解释、不要加代码块标记、"
            + "不要包含用户给的原词。例如输入「限速」应输出 [\"限流\",\"速率限制\",\"流量控制\"]。";

    private final AppConfigRepository configRepository;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public SearchExpandClient(AppConfigRepository configRepository) {
        this.configRepository = configRepository;
    }

    public boolean isEnabled() {
        Map<String, String> config = configRepository.findValues(
                SettingsService.KEY_AI_ENABLED, SettingsService.KEY_AI_BASE_URL, SettingsService.KEY_AI_API_KEY);
        return Boolean.parseBoolean(config.get(SettingsService.KEY_AI_ENABLED))
                && notBlank(config.get(SettingsService.KEY_AI_BASE_URL))
                && notBlank(config.get(SettingsService.KEY_AI_API_KEY));
    }

    /** 调模型并解析出扩展词。失败一律抛异常，由调用方降级成空结果。 */
    public List<String> expand(String query) throws Exception {
        Map<String, String> config = configRepository.findValues(
                SettingsService.KEY_AI_BASE_URL, SettingsService.KEY_AI_MODEL, SettingsService.KEY_AI_API_KEY);
        String baseUrl = String.valueOf(config.get(SettingsService.KEY_AI_BASE_URL)).trim();
        String model = config.get(SettingsService.KEY_AI_MODEL);
        String apiKey = config.get(SettingsService.KEY_AI_API_KEY);
        if (!notBlank(apiKey)) throw new IllegalStateException("AI 未配置");

        String endpoint = baseUrl.endsWith("/chat/completions") ? baseUrl
                : baseUrl.replaceAll("/+$", "") + "/chat/completions";

        Map<String, Object> body = new HashMap<String, Object>();
        body.put("model", notBlank(model) ? model.trim() : "deepseek-chat");
        // 温度压到 0：扩展词要的是稳定可复现，不是创意。同一个词两次搜出不同结果会让人觉得「搜索在飘」。
        body.put("temperature", Double.valueOf(0));
        body.put("max_tokens", Integer.valueOf(120));
        Map<String, String> system = new HashMap<String, String>();
        system.put("role", "system");
        system.put("content", SYSTEM_PROMPT);
        Map<String, String> user = new HashMap<String, String>();
        user.put("role", "user");
        user.put("content", query);
        body.put("messages", new Map[] {system, user});

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set("Authorization", "Bearer " + apiKey.trim());

        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(CONNECT_TIMEOUT_MS);
        factory.setReadTimeout(READ_TIMEOUT_MS);
        RestTemplate restTemplate = new RestTemplate(factory);

        String response = restTemplate.postForObject(endpoint,
                new HttpEntity<Map<String, Object>>(body, headers), String.class);
        if (response == null) throw new IllegalStateException("AI 返回为空");
        JsonNode choices = objectMapper.readTree(response).path("choices");
        if (!choices.isArray() || choices.size() == 0) throw new IllegalStateException("AI 返回缺少 choices");
        String content = choices.get(0).path("message").path("content").asText("").trim();
        return parse(content, query);
    }

    /**
     * 从模型输出里抠出词表。
     *
     * <p>三重过滤，缺一不可：去掉原词（否则联想结果会和关键字结果重复一遍）、
     * 去重、限制在 {@link #MAX_TERMS} 个以内（模型偶尔会一口气给十几条，
     * 那会把结果页冲垮，也会让「联想命中」的权重失衡）。</p>
     */
    private List<String> parse(String content, String query) {
        List<String> terms = new ArrayList<String>();
        if (content == null || content.isEmpty()) return terms;
        Matcher matcher = ARRAY.matcher(content);
        if (!matcher.find()) throw new IllegalStateException("AI 没有返回 JSON 数组");
        try {
            JsonNode node = objectMapper.readTree(matcher.group());
            if (!node.isArray()) throw new IllegalStateException("AI 返回的不是数组");
            String original = query == null ? "" : query.trim().toLowerCase();
            for (JsonNode item : node) {
                String term = item.asText("").trim();
                if (term.isEmpty() || term.length() > 20) continue;
                if (term.toLowerCase().equals(original)) continue;
                if (!terms.contains(term)) terms.add(term);
                if (terms.size() >= MAX_TERMS) break;
            }
        } catch (Exception exception) {
            throw new IllegalStateException("AI 返回的 JSON 无法解析：" + exception.getMessage());
        }
        return terms;
    }

    private boolean notBlank(String value) { return value != null && !value.trim().isEmpty(); }
}
