package com.icecode.workbench.poem;

import java.util.LinkedHashMap;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.icecode.workbench.auth.AppConfigRepository;
import com.icecode.workbench.common.BizException;
import com.icecode.workbench.common.ErrorCode;
import com.icecode.workbench.knowledge.LlmAnswerClient;
import com.icecode.workbench.util.TimeUtil;

/**
 * 诗词品读：调一次 LLM，产出「意境字句」与「与当下生活的联结」两段。
 *
 * <p><b>为什么单独一个模块而不是塞进 {@code knowledge}</b>：知识问答是「按题检索 + 归纳」，
 * 这里是「读一首给定的诗 + 共情」，两者的 prompt、温度、失败语义都不一样；共用一个 Service
 * 只会让两边的 prompt 互相将就。
 *
 * <p><b>缓存为什么落在 {@code app_config} 而不是新开一张表</b>：这是纯粹的
 * key → 一段 JSON 的旁路数据，没有外键、不需要按字段查询、也不需要进回收站。
 * 新开表就要多一个 Flyway 迁移 + 同步 {@code SchemaMigrationTest} 里的表数量与版本号，
 * 收益只有「看起来正规一点」。key 形态见 {@link #cacheKey}。
 *
 * <p><b>失败语义</b>：AI 没配 → 抛 {@code VISION_UNSUPPORTED} 是不对的（那是图片的码），
 * 所以这里走 {@code INTERNAL_ERROR} + 一句能指导操作的话；调用失败同样降级成可读文案，
 * 但**不缓存**失败结果 —— 否则用户点一次「重新生成」永远拿到同一句「AI 暂时没有回应」。
 */
@Service
public class PoemReflectService {

    private static final Logger LOGGER = LoggerFactory.getLogger(PoemReflectService.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** 品读这类文字要的是情味，不是复现性 —— 与知识问答的 0.2 刻意分开。 */
    private static final double TEMPERATURE = 0.85;

    /** 缓存 key 前缀。整串形如 {@code poem.reflect.v1.<hash>}。 */
    private static final String CACHE_PREFIX = "poem.reflect.v1.";

    /**
     * prompt 版本号写进缓存 key：将来改了 systemPrompt，旧缓存自然不再命中，
     * 不需要写一段「清理历史缓存」的代码。改了 prompt 就把这里 +1。
     */
    private static final String PROMPT_VERSION = "1";

    private static final String SYSTEM_PROMPT =
            "你是一位懂诗也懂人的朋友。有人独自读到了一首诗，想听听你的体会。\n"
            + "请写两段文字，像在灯下轻声说话，不要像讲课。\n\n"
            + "第一段「意境与字句」：写这首诗的画面与气息，点出最动人的一两个字句，"
            + "讲讲它们好在哪里。让人读完像亲眼看见了那一幕。\n"
            + "第二段「与你此刻的生活」：把这首诗接到今天的生活里 —— 可以是给一个人的安慰、"
            + "一句很轻的提醒，或是一点说不出口的共鸣。落点要具体，不要讲大道理。\n\n"
            + "写法上的要求（很重要）：\n"
            + "1. 用第二人称「你」，像朋友在耳边说话，有温度。\n"
            + "2. 不要用「一、二、三」或「首先/其次」这类条目化的写法，"
            + "也不要出现「赏析」「意象分析」「综上所述」这类学术词。\n"
            + "3. 每段 90~180 字。宁可精练，不要铺陈。\n"
            + "4. 不要复述整首诗，不要给作者生平，不要加标题以外的任何小标题。\n"
            + "5. 直接输出正文，不要任何前后缀说明。\n\n"
            + "严格按下面的 JSON 输出（不要用 markdown 代码块包裹）：\n"
            + "{\"imagery\":\"第一段正文\",\"affinity\":\"第二段正文\"}";

    private final AppConfigRepository configRepository;
    private final LlmAnswerClient llmClient;

    public PoemReflectService(AppConfigRepository configRepository, LlmAnswerClient llmClient) {
        this.configRepository = configRepository;
        this.llmClient = llmClient;
    }

    public PoemReflectVO reflect(PoemReflectRequest request) {
        if (!llmClient.isEnabled()) {
            throw new BizException(ErrorCode.INTERNAL_ERROR,
                    "还没有配置 AI，先在「设置 → AI」里填好接口地址与密钥，就能听到这首诗的品读了");
        }

        String title = trimToNull(request.getTitle());
        String body = trimToNull(request.getBody());
        // 字段注解已经拦过一次；这里再兜一次是因为本方法也会被「重新生成」以外的入口调用，
        // 空诗去调模型只会烧一次 token 换回一句废话。
        if (title == null || body == null) {
            throw new BizException(ErrorCode.INVALID_PARAMETER, "缺少诗词标题或正文，无法生成品读");
        }

        String key = cacheKey(title, request.getDynasty(), request.getAuthor(), body);
        PoemReflectVO cached = readCache(key);
        if (cached != null) {
            return cached;
        }

        String raw;
        try {
            raw = llmClient.chat(SYSTEM_PROMPT, userPrompt(title, request.getDynasty(), request.getAuthor(), body),
                    TEMPERATURE);
        } catch (Exception e) {
            LOGGER.warn("诗词品读调用 AI 失败：{}", e.getMessage());
            throw new BizException(ErrorCode.INTERNAL_ERROR,
                    "AI 暂时没有回应，先静静读一读这首诗吧，稍后再试一次");
        }

        PoemReflectVO fresh = parse(raw);
        if (fresh == null) {
            // 模型返回了没法用的东西（不是 JSON、或两段都空）。不缓存，让用户重试有意义。
            LOGGER.warn("诗词品读返回的内容无法解析，长度={}", raw == null ? 0 : raw.length());
            throw new BizException(ErrorCode.INTERNAL_ERROR,
                    "AI 这次的回答没能读懂，稍后再试一次吧");
        }

        writeCache(key, fresh);
        return fresh;
    }

    /* ------------------------------------------------------------ prompt */

    private String userPrompt(String title, String dynasty, String author, String body) {
        StringBuilder sb = new StringBuilder();
        sb.append("诗题：").append(title).append('\n');
        String byline = joinByline(dynasty, author);
        if (byline != null) {
            sb.append("「").append(byline).append("」\n");
        }
        sb.append("全文：\n").append(body);
        return sb.toString();
    }

    private String joinByline(String dynasty, String author) {
        String d = trimToNull(dynasty);
        String a = trimToNull(author);
        if (d != null && a != null) return d + " · " + a;
        if (a != null) return a;
        return d;
    }

    /* ------------------------------------------------------------ 解析 */

    /**
     * 容忍模型不听话的三种常见形态：① 用 ```json 包住；② 前后带一句客套话；
     * ③ 字段名大小写/顺序变化。所以先剥代码块、再从第一个 { 到最后 } 之间取子串解析，
     * 最后按字段名取值（大小写不敏感），取不到时才判失败。
     */
    private PoemReflectVO parse(String raw) {
        String text = stripFence(raw);
        if (text == null) return null;
        int start = text.indexOf('{');
        int end = text.lastIndexOf('}');
        if (start < 0 || end <= start) return null;

        String imagery = null;
        String affinity = null;
        try {
            JsonNode root = MAPPER.readTree(text.substring(start, end + 1));
            imagery = textOf(root, "imagery");
            affinity = textOf(root, "affinity");
        } catch (Exception e) {
            return null;
        }

        imagery = trimToNull(imagery);
        affinity = trimToNull(affinity);
        // 两段缺一不可：只给一段就去渲染，全屏页会空掉半屏，比直接报错更难解释。
        if (imagery == null || affinity == null) return null;
        return new PoemReflectVO(imagery, affinity, false);
    }

    private String textOf(JsonNode root, String field) {
        if (root.hasNonNull(field)) return root.get(field).asText();
        // 模型偶尔会写成 Imagery / IMAGERY，兜一下，不值得为它多调一次。
        java.util.Iterator<String> names = root.fieldNames();
        while (names.hasNext()) {
            String name = names.next();
            if (name.equalsIgnoreCase(field)) return root.get(name).asText();
        }
        return null;
    }

    private String stripFence(String raw) {
        String text = trimToNull(raw);
        if (text == null) return null;
        if (!text.startsWith("```")) return text;
        int firstBreak = text.indexOf('\n');
        if (firstBreak < 0) return text;
        int lastFence = text.lastIndexOf("```");
        if (lastFence <= firstBreak) return text;
        return trimToNull(text.substring(firstBreak + 1, lastFence));
    }

    /* ------------------------------------------------------------ 缓存 */

    /**
     * key 里只放「诗本身」的指纹，不放任何用户信息 —— 同一首诗在任何时候打开都该命中。
     * 用 SHA-1 而不是把诗题拼进 key：诗题含中文与「·」等字符，直接当 key 既不安全
     * 也没法保证唯一（不同作者的同名诗是存在的）。
     */
    private String cacheKey(String title, String dynasty, String author, String body) {
        String fingerprint = joinByline(dynasty, author) + "\n" + title + "\n" + body;
        String hash;
        try {
            java.security.MessageDigest md = java.security.MessageDigest.getInstance("SHA-1");
            byte[] digest = md.digest(fingerprint.getBytes("UTF-8"));
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < digest.length; i++) {
                sb.append(String.format("%02x", digest[i]));
            }
            hash = sb.toString();
        } catch (Exception e) {
            // 算法/编码在 JVM 里必然存在，走不到这里；真走到了也不能让整条链路挂掉。
            hash = Integer.toHexString(fingerprint.hashCode());
        }
        return CACHE_PREFIX + PROMPT_VERSION + "." + hash;
    }

    private PoemReflectVO readCache(String key) {
        String stored = configRepository.findValue(key);
        String text = trimToNull(stored);
        if (text == null) return null;
        try {
            JsonNode root = MAPPER.readTree(text);
            String imagery = trimToNull(root.path("imagery").asText(null));
            String affinity = trimToNull(root.path("affinity").asText(null));
            if (imagery == null || affinity == null) return null;
            return new PoemReflectVO(imagery, affinity, true);
        } catch (Exception e) {
            // 缓存坏了就当作没缓存，这次照常调 AI 覆盖掉它。
            LOGGER.warn("诗词品读缓存解析失败，将重新生成：{}", key);
            return null;
        }
    }

    private void writeCache(String key, PoemReflectVO vo) {
        Map<String, String> payload = new LinkedHashMap<String, String>();
        payload.put("imagery", vo.getImagery());
        payload.put("affinity", vo.getAffinity());
        try {
            configRepository.save(key, MAPPER.writeValueAsString(payload), TimeUtil.now());
        } catch (Exception e) {
            // 写缓存失败不该让用户拿不到结果 —— 品读已经生成了，照常返回。
            LOGGER.warn("诗词品读缓存写入失败：{}", e.getMessage());
        }
    }

    /* ------------------------------------------------------------ 杂项 */

    private String trimToNull(String value) {
        if (value == null) return null;
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }
}
