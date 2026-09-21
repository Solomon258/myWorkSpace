package com.icecode.workbench.vision;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
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
import com.icecode.workbench.common.BizException;
import com.icecode.workbench.common.ErrorCode;
import com.icecode.workbench.settings.SettingsService;
import com.icecode.workbench.util.TextUtil;
import com.icecode.workbench.util.TimeUtil;

/**
 * 外部视觉模型（OpenAI 兼容接口）。
 *
 * <p>这是项目里第 4 份 OpenAI 兼容客户端（前 3 份：{@code LlmClassifyProvider} /
 * {@code SearchExpandClient} / {@code LlmAnswerClient}）。之所以没有抽公共基类：
 * 三份的差异不只在 endpoint，还在超时（这里要 60s，文本整理 10s 就够）、
 * 请求体形态（多模态 content 是数组不是字符串）、以及失败语义（这里失败必须让整条
 * 解析任务失败，而搜索联想失败要完全静默）。强行合并会得到一个到处是 if 的基类。</p>
 *
 * <p><b>隐私</b>：图片会被 base64 后发到配置的第三方服务。这是用户明确知道的
 * （设置页有开关与说明），因此这里不再二次确认，但**日志里绝不出现图片内容或密钥**。</p>
 */
@Service
public class VisionProvider {

    private static final Logger LOGGER = LoggerFactory.getLogger(VisionProvider.class);

    /** 与视觉得分对照的阈值：低于它前端会标「待人工确认」。批量确认那条路另行排除图片条目。 */
    private static final double NEEDS_CONFIRM_THRESHOLD = 0.70;

    private static final int MAX_TITLE_LENGTH = 200;
    private static final int MAX_EVIDENCE_LENGTH = 300;
    /** 单张图解析出的条目上限：再多就不是「一张截图里的几件事」，而是模型跑飞了。 */
    private static final int MAX_ITEMS_PER_IMAGE = 12;

    private static final Pattern DATE = Pattern.compile("\\d{4}-\\d{2}-\\d{2}");
    private static final Pattern CLOCK = Pattern.compile("([01]\\d|2[0-3]):[0-5]\\d");

    private static final String SYSTEM_PROMPT = buildSystemPrompt();

    private final AppConfigRepository configRepository;
    private final ImageDownscaler downscaler;
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final AtomicLong promptTokens = new AtomicLong();
    private final AtomicLong completionTokens = new AtomicLong();

    public VisionProvider(AppConfigRepository configRepository, ImageDownscaler downscaler) {
        this.configRepository = configRepository;
        this.downscaler = downscaler;
    }

    /**
     * 视觉解析是否可用。
     *
     * <p>三个条件缺一不可：总开关开着、base_url 与 api_key 齐备、**而且填了视觉模型名**。
     * 第三项最容易漏 —— 同一个 base_url 下文本模型与视觉模型通常不同名（如
     * {@code deepseek-chat} 与 {@code gpt-4o-mini}），拿文本模型名去发图片，
     * 服务端会回一句「当前模型不支持图片输入」，用户完全不知道要去哪儿改。</p>
     */
    public boolean isConfigured() {
        Map<String, String> config = configRepository.findValues(
                SettingsService.KEY_AI_ENABLED, SettingsService.KEY_AI_BASE_URL,
                SettingsService.KEY_AI_API_KEY, SettingsService.KEY_AI_VISION_MODEL);
        return Boolean.parseBoolean(config.get(SettingsService.KEY_AI_ENABLED))
                && notBlank(config.get(SettingsService.KEY_AI_BASE_URL))
                && notBlank(config.get(SettingsService.KEY_AI_API_KEY))
                && notBlank(config.get(SettingsService.KEY_AI_VISION_MODEL));
    }

    /**
     * 指出「还不能解析图片」到底缺哪一项，直接给出可执行的中文原因。
     *
     * <p>为什么要有这个方法：{@link #isConfigured()} 是个四合一布尔值，四个条件里
     * 任何一个不满足都只能回一句笼统的「请填写视觉模型」。实测最容易踩的恰恰是
     * **总开关没打开**（或 base_url / api_key 空着）—— 用户照着提示去填了模型名，
     * 回来发现还是不行，因为病根根本不在那儿。所以要逐项检查、指哪儿打哪儿。</p>
     *
     * @return 缺失项的说明；全部齐备时返回 {@code null}
     */
    public String describeMissingConfig() {
        Map<String, String> config = configRepository.findValues(
                SettingsService.KEY_AI_ENABLED, SettingsService.KEY_AI_BASE_URL,
                SettingsService.KEY_AI_API_KEY, SettingsService.KEY_AI_VISION_MODEL);
        if (!Boolean.parseBoolean(config.get(SettingsService.KEY_AI_ENABLED))) {
            return "AI 功能总开关没打开。请到「设置 → AI」勾选「启用 AI」，图片解析依赖它";
        }
        if (notBlank(config.get(SettingsService.KEY_AI_BASE_URL)) == false) {
            return "AI 服务地址（base_url）没填。请到「设置 → AI」填写，例如 https://api.deepseek.com";
        }
        if (notBlank(config.get(SettingsService.KEY_AI_API_KEY)) == false) {
            return "AI API Key 没填。请到「设置 → AI」填写你的密钥";
        }
        if (notBlank(config.get(SettingsService.KEY_AI_VISION_MODEL)) == false) {
            return "「视觉模型名」没填。请到「设置 → AI」填写能读图的模型名"
                    + "（DeepSeek 请填 deepseek-flash，不要填产品名 deepseek-V4.1-Flash）";
        }
        return null;
    }

    /** 取出并重置自上次以来的 token 用量（供 ai_job 记账）。 */
    public long[] drainTokens() {
        return new long[]{promptTokens.getAndSet(0), completionTokens.getAndSet(0)};
    }

    /**
     * 解析一张图。
     *
     * @param imagePath 磁盘上的原图（{@code AttachmentService.pathOf} 给出的路径）
     * @param timezone  用于把「今天/明天/下周三」这类相对表述换算成绝对日期
     * @throws BizException 未配置视觉模型（3015）或识别失败（3016）
     */
    public VisionResult parse(Path imagePath, String timezone) {
        if (!isConfigured()) {
            String reason = describeMissingConfig();
            throw new BizException(ErrorCode.VISION_UNSUPPORTED,
                    reason != null ? reason : ErrorCode.VISION_UNSUPPORTED.getMessage());
        }
        Map<String, String> config = configRepository.findValues(
                SettingsService.KEY_AI_BASE_URL, SettingsService.KEY_AI_VISION_MODEL, SettingsService.KEY_AI_API_KEY);
        String baseUrl = config.get(SettingsService.KEY_AI_BASE_URL).trim();
        String model = config.get(SettingsService.KEY_AI_VISION_MODEL).trim();
        String apiKey = config.get(SettingsService.KEY_AI_API_KEY);

        String endpoint = baseUrl.endsWith("/chat/completions") ? baseUrl
                : baseUrl.replaceAll("/+$", "") + "/chat/completions";

        // 先降采样再编码：原图 10MB → 通常 300KB 上下，base64 后仍在服务端限额内。
        byte[] bytes = downscaler.toJpegBytes(imagePath);
        String mimeType = "image/jpeg";
        if (bytes == null) {
            bytes = downscaler.readRaw(imagePath);
            mimeType = "application/octet-stream";
        }
        if (bytes == null || bytes.length == 0) {
            throw new BizException(ErrorCode.VISION_FAILED, "读不到这张图片的内容，可能文件已损坏");
        }

        Map<String, Object> body = buildBody(model, timezone, bytes, mimeType);
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set("Authorization", "Bearer " + apiKey.trim());

        // 视觉推理明显比文本慢：60s 读超时是「大图 + 慢模型」的合理上界。
        // 注意这个调用**不持有任何数据库事务**（见 VisionParseService 的说明）。
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(5000);
        factory.setReadTimeout(60000);
        RestTemplate restTemplate = new RestTemplate(factory);

        String response;
        try {
            response = restTemplate.postForObject(endpoint,
                    new HttpEntity<Map<String, Object>>(body, headers), String.class);
        } catch (Exception exception) {
            // 不把异常原文直接给用户：里面常带完整 URL 与请求体摘要（含 base64 图片）。
            LOGGER.warn("视觉解析请求失败：{}", exception.getClass().getSimpleName() + " " + String.valueOf(exception.getMessage()));
            throw new BizException(ErrorCode.VISION_FAILED,
                    "调用视觉模型失败（" + exception.getClass().getSimpleName() + "），请检查设置里的服务地址与视觉模型名");
        }
        return parseResponse(response);
    }

    private Map<String, Object> buildBody(String model, String timezone, byte[] bytes, String mimeType) {
        Map<String, Object> body = new HashMap<String, Object>();
        body.put("model", model);
        body.put("temperature", Double.valueOf(0));

        // 关掉「思考模式」（2026-09-16 实测后加的）。
        // 新一代模型（如 DeepSeek V4.1-Flash）默认开思考，同一张微信截图实测：
        //   默认开思考 → 8.2s / 输出 1648 token
        //   关思考     → 2.1s / 输出  209 token   ← 识别结果完全相同
        // 这是**感知型任务**（读图 + 摘要素），不需要长链推理；而且解析是单线程串行的，
        // 一张图快 4 倍直接决定了用户拖多张截图后的等待时长。输出 token 通常还是计费大头。
        // 不支持的模型会忽略这个字段，因此对其它服务商的模型无害。
        Map<String, Object> thinking = new HashMap<String, Object>();
        thinking.put("type", "disabled");
        body.put("thinking", thinking);

        Map<String, String> textPart = new HashMap<String, String>();
        textPart.put("type", "text");
        textPart.put("text", "时区：" + timezone + "，今天：" + TimeUtil.today(timezone)
                + "。请读出这张图里的内容并整理成待办。");

        Map<String, Object> imageUrl = new HashMap<String, Object>();
        imageUrl.put("url", "data:" + mimeType + ";base64," + Base64.getEncoder().encodeToString(bytes));
        Map<String, Object> imagePart = new HashMap<String, Object>();
        imagePart.put("type", "image_url");
        imagePart.put("image_url", imageUrl);

        // content 是**数组**（多模态格式），不是字符串 —— 写成字符串服务端会当成纯文本，
        // 图片被静默忽略，模型回答「我没有看到图片」。
        List<Object> content = new ArrayList<Object>();
        content.add(textPart);
        content.add(imagePart);

        Map<String, Object> system = new HashMap<String, Object>();
        system.put("role", "system");
        system.put("content", SYSTEM_PROMPT);
        Map<String, Object> user = new HashMap<String, Object>();
        user.put("role", "user");
        user.put("content", content);
        List<Map<String, Object>> messages = new ArrayList<Map<String, Object>>();
        messages.add(system);
        messages.add(user);
        body.put("messages", messages);
        return body;
    }

    private VisionResult parseResponse(String response) {
        if (response == null) {
            throw new BizException(ErrorCode.VISION_FAILED, "视觉模型返回为空");
        }
        JsonNode root;
        try {
            root = objectMapper.readTree(response);
        } catch (Exception exception) {
            throw new BizException(ErrorCode.VISION_FAILED, "视觉模型返回的不是合法 JSON");
        }
        JsonNode usage = root.path("usage");
        if (usage.isObject()) {
            promptTokens.addAndGet(usage.path("prompt_tokens").asLong(0));
            completionTokens.addAndGet(usage.path("completion_tokens").asLong(0));
        }
        JsonNode choices = root.path("choices");
        if (!choices.isArray() || choices.size() == 0) {
            // 有些 OpenAI 兼容服务把错误放在 error.message 里，把它带出来才可诊断。
            String upstream = root.path("error").path("message").asText("");
            throw new BizException(ErrorCode.VISION_FAILED,
                    upstream.isEmpty() ? "视觉模型返回缺少 choices" : "视觉模型报错：" + clip(upstream, 200));
        }
        String content = stripFence(choices.get(0).path("message").path("content").asText(""));
        JsonNode result;
        try {
            result = objectMapper.readTree(content);
        } catch (Exception exception) {
            throw new BizException(ErrorCode.VISION_FAILED, "视觉模型输出不是合法 JSON（可换一个视觉模型试试）");
        }

        VisionResult parsed = new VisionResult();
        parsed.setRawText(clip(result.path("rawText").asText(""), 20000));
        JsonNode items = result.path("items");
        if (items.isArray()) {
            for (JsonNode node : items) {
                if (parsed.getItems().size() >= MAX_ITEMS_PER_IMAGE) {
                    break;
                }
                VisionItem item = toItem(node);
                if (item != null) {
                    parsed.getItems().add(item);
                }
            }
        }
        if (parsed.getRawText().isEmpty() && parsed.getItems().isEmpty()) {
            throw new BizException(ErrorCode.VISION_FAILED, "这张图里没读出任何内容，换一张更清晰的试试");
        }
        return parsed;
    }

    private VisionItem toItem(JsonNode node) {
        String category = node.path("category").asText("").trim();
        // 分类不合法的条目直接丢：硬塞成 favorite 会让用户在整理页看到一条他自己从没确认过的东西。
        if (!"task".equals(category) && !"schedule".equals(category)
                && !"favorite".equals(category) && !"knowledge".equals(category)) {
            return null;
        }
        VisionItem item = new VisionItem();
        item.setCategory(category);
        double confidence = node.path("confidence").isNumber() ? node.path("confidence").asDouble(0.75) : 0.75;
        item.setConfidence(Math.max(0, Math.min(1, confidence)));
        String title = node.path("title").asText("").trim();
        item.setTitle(title.isEmpty() ? null : TextUtil.clip(title, MAX_TITLE_LENGTH));
        item.setDue(validOrNull(node.path("due").asText(null), DATE));
        String priority = node.path("priority").asText("P2").trim();
        item.setPriority(priority.matches("P[0-3]") ? priority : "P2");
        item.setStart(validOrNull(node.path("start").asText(null), CLOCK));
        item.setEnd(validOrNull(node.path("end").asText(null), CLOCK));
        String eventType = node.path("eventType").asText(null);
        item.setEventType("meeting".equals(eventType) || "deep_block".equals(eventType) || "other".equals(eventType)
                ? eventType : ("schedule".equals(category) ? "meeting" : null));
        String evidence = node.path("evidence").asText("").trim();
        item.setEvidence(evidence.isEmpty() ? null : TextUtil.clip(evidence, MAX_EVIDENCE_LENGTH));
        return item;
    }

    /**
     * 系统提示词。
     *
     * <p>三个刻意的约束，都是为了防止模型「好心办坏事」：</p>
     * <ul>
     *   <li><b>没有的事不要编</b>：日期/时间只能来自图中的文字，读不出就给 null。
     *       让模型「推测」出今天是截止日期，会把一条没有期限的任务变成逾期任务。</li>
     *   <li><b>一条一例，别合并</b>：图里三件事就要输出三条。合并之后用户在整理页
     *       只能确认成一条，另外两条永久丢失且没有任何提示。</li>
     *   <li><b>带 evidence</b>：每条都给出对应原文片段，用户核对时才有据可依。</li>
     * </ul>
     */
    private static String buildSystemPrompt() {
        return "你是一个把图片整理成待办的助手。读出图里的文字，并整理成若干条待办事项。"
                + "只输出 JSON，不要输出任何其他文字。格式："
                + "{\"rawText\":\"图中读出的全部文字（保留原有换行，不要总结）\","
                + "\"items\":[{\"category\":\"task|schedule|favorite|knowledge\","
                + "\"confidence\":0到1之间的小数,"
                + "\"title\":\"不超过24字的短标题\","
                + "\"due\":\"yyyy-MM-dd或null\","
                + "\"priority\":\"P0|P1|P2|P3\","
                + "\"start\":\"HH:mm或null\",\"end\":\"HH:mm或null\","
                + "\"eventType\":\"meeting|deep_block|other或null\","
                + "\"evidence\":\"这条对应的图中原文片段\"}]}。"
                + "规则："
                + "① 图中每一件事单独输出一条，不要合并；没有任何待办内容时 items 输出空数组。"
                + "② due / start / end 只能来自图中写出的日期与时间，读不出来必须输出 null，"
                + "绝对不要根据「今天」推算或猜测。"
                + "③ 「是否值得长期沉淀的知识」归到 knowledge，普通提醒归 favorite。"
                + "④ title 用中文，去掉语气词，不要带书名号与引号。";
    }

    private String stripFence(String content) {
        String text = content == null ? "" : content.trim();
        if (text.startsWith("```")) {
            int firstNewline = text.indexOf('\n');
            if (firstNewline > 0) {
                text = text.substring(firstNewline + 1);
            }
            if (text.endsWith("```")) {
                text = text.substring(0, text.length() - 3);
            }
        }
        return text.trim();
    }

    private String validOrNull(String value, Pattern pattern) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return pattern.matcher(trimmed).matches() ? trimmed : null;
    }

    private String clip(String value, int max) {
        return TextUtil.clip(value == null ? "" : value, max);
    }

    private boolean notBlank(String value) {
        return value != null && !value.trim().isEmpty();
    }
}
