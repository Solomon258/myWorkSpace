package com.icecode.workbench.knowledge;

import java.util.HashMap;
import java.util.Map;

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
 * 知识问答用的 OpenAI 兼容对话客户端（DeepSeek baseUrl/model 均可换，设置页维护）。
 * 与整理分类共用同一份 AI 配置；密钥只出现在请求头。任何异常直接抛出，由调用方降级。
 */
@Service
public class LlmAnswerClient {

    private final AppConfigRepository configRepository;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public LlmAnswerClient(AppConfigRepository configRepository) {
        this.configRepository = configRepository;
    }

    public boolean isEnabled() {
        Map<String, String> config = configRepository.findValues(
                SettingsService.KEY_AI_ENABLED, SettingsService.KEY_AI_BASE_URL, SettingsService.KEY_AI_API_KEY);
        return Boolean.parseBoolean(config.get(SettingsService.KEY_AI_ENABLED))
                && notBlank(config.get(SettingsService.KEY_AI_BASE_URL))
                && notBlank(config.get(SettingsService.KEY_AI_API_KEY));
    }

    public String chat(String systemPrompt, String userPrompt) throws Exception {
        Map<String, String> config = configRepository.findValues(
                SettingsService.KEY_AI_BASE_URL, SettingsService.KEY_AI_MODEL, SettingsService.KEY_AI_API_KEY);
        String baseUrl = config.get(SettingsService.KEY_AI_BASE_URL).trim();
        String model = config.get(SettingsService.KEY_AI_MODEL);
        String apiKey = config.get(SettingsService.KEY_AI_API_KEY);
        if (!notBlank(apiKey)) throw new IllegalStateException("AI 未配置");

        String endpoint = baseUrl.endsWith("/chat/completions") ? baseUrl
                : baseUrl.replaceAll("/+$", "") + "/chat/completions";

        Map<String, Object> body = new HashMap<String, Object>();
        body.put("model", notBlank(model) ? model.trim() : "deepseek-chat");
        body.put("temperature", Double.valueOf(0.2));
        Map<String, String> system = new HashMap<String, String>();
        system.put("role", "system");
        system.put("content", systemPrompt);
        Map<String, String> user = new HashMap<String, String>();
        user.put("role", "user");
        user.put("content", userPrompt);
        body.put("messages", new Map[]{system, user});

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set("Authorization", "Bearer " + apiKey.trim());

        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(3000);
        factory.setReadTimeout(20000);
        RestTemplate restTemplate = new RestTemplate(factory);

        String response = restTemplate.postForObject(endpoint, new HttpEntity<Map<String, Object>>(body, headers), String.class);
        if (response == null) throw new IllegalStateException("AI 返回为空");
        JsonNode root = objectMapper.readTree(response);
        JsonNode choices = root.path("choices");
        if (!choices.isArray() || choices.size() == 0) throw new IllegalStateException("AI 返回缺少 choices");
        String content = choices.get(0).path("message").path("content").asText("").trim();
        if (content.isEmpty()) throw new IllegalStateException("AI 返回内容为空");
        return content;
    }

    private boolean notBlank(String value) { return value != null && !value.trim().isEmpty(); }
}
