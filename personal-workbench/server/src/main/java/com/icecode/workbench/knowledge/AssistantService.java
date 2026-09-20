package com.icecode.workbench.knowledge;

import java.io.UnsupportedEncodingException;
import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import com.icecode.workbench.auth.AppConfigRepository;
import com.icecode.workbench.auth.AuthConstants;
import com.icecode.workbench.common.BizException;
import com.icecode.workbench.common.ErrorCode;
import com.icecode.workbench.inbox.InboxRepository;
import com.icecode.workbench.util.TimeUtil;

/**
 * 知识库问答（轻量 RAG）：Vault 只读索引（按标题分片）→ CJK 二元组检索
 * → 已配置 LLM（DeepSeek 等 OpenAI 兼容接口）则把命中片段归纳成一段完整答案，
 * 否则/失败时降级为多片段摘录；检索不到足够相关内容时把问题收录为「[待补知识]」进收集箱。
 *
 * <p><b>回答的形态（2026-09-18 定）</b>：答案正文在前（对问题的一段完整归纳），
 * 出处以编号列表附在答案之后。早期实现两者权重一样，用户看到的是「几个文档块」，
 * 于是反馈「回答只列出文档链接」——所以两件事必须分开呈现：
 * 正文回答「是什么」，来源列表只负责「去哪儿看原文」。
 *
 * <p><b>检索为什么不是简单的二元组计数</b>：Vault 里 3000+ 个片段，而笔记小标题里
 * 到处是「怎么设计」「如何做」。等权计数会让「复盘应该怎么做」命中的全是讲
 * 「怎么设计数据库」的笔记（实测 top3 全部无关，模型只能回答「笔记里相关信息有限」）。
 * 现在的三层是：①剥离疑问/功能词，避免把问句本身的措辞当成检索词；
 * ②按语料内的出现频率（idf）给词加权，越常见的词越不值钱；
 * ③必须至少命中一个「稀有词」（出现比例 ≤ {@link #RARE_RATIO}）才算相关，
 * 否则宁可如实回答「没找到」并把问题收进收集箱。
 *
 * <p>索引本身（扫描 + 剪枝 + 快照缓存 + 落盘）在 {@link ObsidianVaultService#vaultIndex()}，
 * 本类只消费快照，不再自己缓存 —— 缓存与失效规则各写一份，必然漂。
 */
@Service
public class AssistantService {

    private static final Logger LOGGER = LoggerFactory.getLogger(AssistantService.class);

    /** 一次问答最多引用几个片段。 */
    private static final int MAX_USED_CHUNKS = 4;
    /** 同一个文件最多取几个片段 —— 全取自同一篇笔记，归纳出来就只是那一篇的摘要。 */
    private static final int MAX_PER_FILE = 2;
    /** 单个片段进上下文的字符上限（索引里的片段本身最多 1200 字）。 */
    private static final int CHUNK_BUDGET = 1200;
    /** 上下文总量上限，避免几篇长笔记把请求撑爆。 */
    private static final int CONTEXT_BUDGET = 4400;
    /** 得分不到最高分这个比例的片段不配当「相关来源」——留着只会稀释上下文。 */
    private static final double RELATIVE_CUTOFF = 0.40;
    /** 引用列表里的摘录长度。 */
    private static final int SNIPPET_CHARS = 90;

    /**
     * 「稀有词」的判定：在索引里出现比例不超过 {@link #RARE_RATIO} 的词，才算能把
     * 一篇笔记和这个问题绑在一起。用比例而非固定值，是因为 Vault 规模差两个数量级
     * （测试里一两篇笔记，实际 3000+ 个片段）。下限 {@link #RARE_FLOOR} 保证小 Vault
     * 里任何一次命中都算数，否则短问题会一律被判成「不相关」。
     */
    private static final double RARE_RATIO = 0.05;
    private static final int RARE_FLOOR = 2;
    /**
     * 片段数低于这个量级时「出现比例」没有统计意义（刚建的库只有两三篇笔记，
     * 任何词都会占满 100%），此时一律按稀有处理，否则空库起步的阶段会一个问题都答不出来。
     */
    private static final int MIN_STATS_CHUNKS = 20;

    /**
     * 问句里的疑问词与功能词。它们几乎出现在每篇笔记的小标题里（「怎么设计」「如何做好」），
     * 留着参与打分就等于拿问句的措辞去匹配笔记。剥离之后剩下的才是问题的内容词。
     * <b>顺序要紧</b>：长的写在前面，「怎么样」才不会被「怎么」先吃掉。
     */
    private static final String[] QUESTION_NOISE = {
            "请问", "怎么样", "怎么办", "怎么做", "为什么", "是什么", "有什么样", "有什么",
            "什么样", "是怎样的", "是怎么", "怎么", "如何", "怎样", "什么", "为何",
            "是否", "能否", "能不能", "可不可以", "可以", "应该", "需要", "有哪些", "哪些",
            "哪个", "哪种", "多少", "介绍", "说说", "讲讲", "告诉我", "帮我", "一下"
    };

    private static final Pattern NOISE_PATTERN = compileNoise();
    private static final Pattern SEPARATOR_PATTERN = Pattern.compile("[\\s？?，。！!、：:；;（）()【】\\[\\]\"'“”‘’·…—\\-]+");
    private static final Pattern CJK_PATTERN = Pattern.compile("[\\u4e00-\\u9fff]");
    /**
     * 中文词尾助词。只从长度 ≥3 的中文串尾部剥离，为的是消掉「设计的」「限流是」这类
     * 跨词边界的二元组（它们看似稀有，其实是常用短语的碎片）；
     * 长度 2 的串不动，否则「目的」会被剥成「目」。
     */
    private static final String TAIL_PARTICLES = "的了是呢吧吗啊呀哦";

    private static final String ANSWER_SYSTEM_PROMPT =
            "你是用户的个人知识库助手。下面给出若干条来自用户 Obsidian 笔记的片段，每条带编号。\n"
                    + "请把它们归纳整合成一段完整的回答，要求：\n"
                    + "1. 直接回答问题，先给结论，再展开要点或做法；用中文，一般 150~400 字，问题简单就写短一些。\n"
                    + "2. 把多条片段的信息合并、按逻辑重新组织，不要逐条摘抄，也不要写「笔记1说了…笔记2说了…」。\n"
                    + "3. 在用到的信息后面标注片段编号，如 [1]、[1][2]；编号必须真实存在，不要编造。\n"
                    + "4. 片段只能回答一部分时，就只回答这一部分，并说明笔记里还缺什么；"
                    + "绝不能编造片段中没有的事实、数字或人名。\n"
                    + "5. 不要复述问题，不要自己写「参考来源」「出处」这类小节，也不要使用 Markdown 标题、"
                    + "星号或代码块 —— 出处由系统附在答案之后。";

    private static final String NO_MATCH_ANSWER =
            "知识库中没有找到足够相关的内容。这条问题本身值得记录——已收录到收集箱，确认后可写入 Vault。";

    private final ObsidianVaultService vaultService;
    private final LlmAnswerClient llmClient;
    private final InboxRepository inboxRepository;
    private final AppConfigRepository configRepository;

    public AssistantService(ObsidianVaultService vaultService, LlmAnswerClient llmClient,
                            InboxRepository inboxRepository, AppConfigRepository configRepository) {
        this.vaultService = vaultService;
        this.llmClient = llmClient;
        this.inboxRepository = inboxRepository;
        this.configRepository = configRepository;
    }

    public IndexStatusVO indexStatus() {
        if (!vaultService.isConfigured()) {
            return new IndexStatusVO(false, "", 0, 0, llmClient.isEnabled());
        }
        // 索引的扫描、缓存与落盘统一由 ObsidianVaultService 负责：
        // 这里以前自带一份「每次调用都算指纹」的缓存，是「打开知识库要等十几秒」的根源。
        ObsidianVaultService.VaultIndex index = vaultService.vaultIndex();
        return new IndexStatusVO(true, vaultService.vaultName(), index.fileCount, index.chunkCount,
                llmClient.isEnabled());
    }

    public AskResponseVO ask(String question) {
        String q = question == null ? "" : question.trim();
        if (q.isEmpty() || q.length() > 500) throw new BizException(ErrorCode.INVALID_PARAMETER, "提问内容不能为空，且不能超过 500 字");
        if (!vaultService.isConfigured()) throw new BizException(ErrorCode.VAULT_NOT_CONFIGURED);

        List<ObsidianVaultService.VaultChunk> chunks = vaultService.vaultIndex().chunks;
        List<ObsidianVaultService.VaultChunk> used = retrieve(chunks, q);
        if (used.isEmpty()) {
            // inbox_item.source 约束仅允许 web/wecom，待补知识按 web 收录
            inboxRepository.insert("[待补知识] " + q, "text", "web", now());
            return new AskResponseVO(false, true, false, NO_MATCH_ANSWER,
                    new ArrayList<AskResponseVO.Citation>());
        }

        boolean llmUsed = false;
        String answer;
        if (llmClient.isEnabled()) {
            try {
                answer = synthesize(q, used);
                llmUsed = true;
            } catch (Exception exception) {
                LOGGER.warn("LLM 问答失败，降级摘录模式：{}", exception.getMessage());
                answer = extractive(used);
            }
        } else {
            answer = extractive(used);
        }

        List<AskResponseVO.Citation> citations = new ArrayList<AskResponseVO.Citation>();
        for (int i = 0; i < used.size(); i++) {
            ObsidianVaultService.VaultChunk chunk = used.get(i);
            citations.add(new AskResponseVO.Citation(i + 1, chunk.file, chunk.heading,
                    clip(chunk.content, SNIPPET_CHARS),
                    "obsidian://open?vault=" + urlEncode(vaultService.vaultName()) + "&file=" + urlEncode(chunk.file)));
        }
        return new AskResponseVO(true, false, llmUsed, answer, citations);
    }

    // ------------------------------------------------------------------ 检索

    /**
     * 从索引里挑出「值得喂给模型」的片段。返回空列表 = 没有足够相关的内容。
     *
     * <p>打分只在「稀有词」上进行（见类注释）。得分高的是「命中稀有词且命中位置很关键」
     * （小标题 > 文件名 > 正文），按分数取前几个，但同一个文件最多
     * {@link #MAX_PER_FILE} 个 —— 否则一问「限流」，四段全来自同一篇长笔记，
     * 归纳出来的就只是那篇的摘要，而不是知识库对这个问题的回答。
     */
    private List<ObsidianVaultService.VaultChunk> retrieve(List<ObsidianVaultService.VaultChunk> chunks, String question) {
        List<ObsidianVaultService.VaultChunk> empty = new ArrayList<ObsidianVaultService.VaultChunk>();
        if (chunks.isEmpty()) return empty;

        List<String> terms = terms(question);
        if (terms.isEmpty()) return empty;

        Map<String, Integer> frequencies = new LinkedHashMap<String, Integer>();
        Map<String, Double> weights = new LinkedHashMap<String, Double>();
        for (String term : terms) {
            int frequency = 0;
            for (ObsidianVaultService.VaultChunk chunk : chunks) {
                if (chunk.matches(term)) frequency++;
            }
            frequencies.put(term, frequency);
            weights.put(term, idf(chunks.size(), frequency));
        }

        int rareCeiling = chunks.size() < MIN_STATS_CHUNKS
                ? chunks.size()
                : Math.max(RARE_FLOOR, (int) (chunks.size() * RARE_RATIO));
        List<String> scoringTerms = new ArrayList<String>();
        for (String term : terms) {
            int frequency = frequencies.get(term);
            if (frequency > 0 && frequency <= rareCeiling) scoringTerms.add(term);
        }
        // 一个稀有词都没命中：说明问题里的内容词在这个 Vault 里要么不存在、要么满库都是。
        // 两种情况下给出「文档链接」都是在骗人，如实回答「没找到」更有用。
        if (scoringTerms.isEmpty()) return empty;

        final List<Double> scores = new ArrayList<Double>(chunks.size());
        List<Integer> ranked = new ArrayList<Integer>(chunks.size());
        for (int i = 0; i < chunks.size(); i++) {
            scores.add(score(chunks.get(i), scoringTerms, weights));
            ranked.add(i);
        }
        // Collections.sort 是稳定排序，同分时保留索引里的原顺序 —— 检索结果要可复现。
        Collections.sort(ranked, new Comparator<Integer>() {
            @Override public int compare(Integer left, Integer right) {
                return Double.compare(scores.get(right), scores.get(left));
            }
        });

        double best = scores.get(ranked.get(0));
        if (best <= 0) return empty;

        List<ObsidianVaultService.VaultChunk> used = new ArrayList<ObsidianVaultService.VaultChunk>();
        Map<String, Integer> perFile = new HashMap<String, Integer>();
        for (Integer index : ranked) {
            if (used.size() >= MAX_USED_CHUNKS) break;
            if (scores.get(index) < best * RELATIVE_CUTOFF) break;
            ObsidianVaultService.VaultChunk chunk = chunks.get(index);
            Integer taken = perFile.get(chunk.file);
            if (taken != null && taken >= MAX_PER_FILE) continue;
            perFile.put(chunk.file, taken == null ? 1 : taken + 1);
            used.add(chunk);
        }
        return used;
    }

    private double score(ObsidianVaultService.VaultChunk chunk, List<String> scoringTerms, Map<String, Double> weights) {
        double total = 0;
        for (String term : scoringTerms) {
            double weight = weights.get(term);
            if (chunk.headingLower().contains(term)) total += weight * 3.0;
            else if (chunk.fileLower().contains(term)) total += weight * 1.5;
            else if (chunk.contentLower().contains(term)) total += weight;
        }
        return total;
    }

    /** 词的信息量：越少见越值钱。1 是为了让「满库都有」的词不至于归零（还有位置加权兜着）。 */
    private double idf(int totalChunks, int frequency) {
        return Math.log(1.0 + (double) totalChunks / (1.0 + frequency));
    }

    /** 检索词：先剥疑问词，再按中文二元组 / 拉丁词切分，去重保序。 */
    private List<String> terms(String question) {
        List<String> terms = new ArrayList<String>();
        Set<String> seen = new HashSet<String>();
        String stripped = NOISE_PATTERN.matcher(question).replaceAll(" ");
        String cleaned = SEPARATOR_PATTERN.matcher(stripped).replaceAll(" ");
        for (String token : cleaned.split(" ")) {
            if (token.isEmpty()) continue;
            if (CJK_PATTERN.matcher(token).find()) {
                StringBuilder run = new StringBuilder();
                for (int i = 0; i < token.length(); i++) {
                    char ch = token.charAt(i);
                    if (ch >= 0x4E00 && ch <= 0x9FFF) {
                        run.append(ch);
                    } else {
                        emitCjk(terms, seen, run);
                        if (Character.isLetterOrDigit(ch)) run.append(ch);
                    }
                }
                emitCjk(terms, seen, run);
            } else if (token.length() >= 2) {
                addTerm(terms, seen, token.toLowerCase());
            }
        }
        return terms;
    }

    private void emitCjk(List<String> terms, Set<String> seen, StringBuilder run) {
        String text = run.toString();
        run.setLength(0);
        if (text.length() >= 3 && TAIL_PARTICLES.indexOf(text.charAt(text.length() - 1)) >= 0) {
            text = text.substring(0, text.length() - 1);
        }
        if (text.length() == 1) {
            addTerm(terms, seen, text);
            return;
        }
        for (int i = 0; i + 2 <= text.length(); i++) addTerm(terms, seen, text.substring(i, i + 2));
    }

    private void addTerm(List<String> terms, Set<String> seen, String term) {
        if (term.isEmpty() || !seen.add(term)) return;
        terms.add(term);
    }

    private static Pattern compileNoise() {
        StringBuilder builder = new StringBuilder();
        for (String word : QUESTION_NOISE) {
            if (builder.length() > 0) builder.append('|');
            builder.append(Pattern.quote(word));
        }
        return Pattern.compile(builder.toString());
    }

    // -------------------------------------------------------------- 答案合成

    private String synthesize(String question, List<ObsidianVaultService.VaultChunk> used) throws Exception {
        StringBuilder context = new StringBuilder();
        int budget = CONTEXT_BUDGET;
        for (int i = 0; i < used.size(); i++) {
            ObsidianVaultService.VaultChunk chunk = used.get(i);
            String body = clip(chunk.content, Math.min(CHUNK_BUDGET, Math.max(0, budget)));
            budget -= body.length();
            context.append("【").append(i + 1).append("】").append(chunk.file)
                    .append(" · ").append(chunk.heading).append("\n")
                    .append(body).append("\n\n");
        }
        String user = "问题：" + question + "\n\n以下是笔记片段：\n" + context;
        return llmClient.chat(ANSWER_SYSTEM_PROMPT, user);
    }

    /**
     * 没配 AI（或调用失败）时的兜底：把命中的片段摘出来，按编号列成几条，
     * 每条注出处。以前只摘第一条的前 140 字，读起来就像「只给了一个文档链接」。
     */
    private String extractive(List<ObsidianVaultService.VaultChunk> used) {
        StringBuilder answer = new StringBuilder("根据你的笔记，相关内容集中在这几处：");
        for (int i = 0; i < used.size(); i++) {
            ObsidianVaultService.VaultChunk chunk = used.get(i);
            answer.append("\n[").append(i + 1).append("]（").append(chunk.file).append(" · ")
                    .append(chunk.heading).append("）")
                    .append(clip(chunk.content, 160));
        }
        return answer.toString();
    }

    private String clip(String text, int max) {
        if (text == null) return "";
        return text.length() <= max ? text : text.substring(0, max) + "…";
    }

    private String urlEncode(String value) {
        try { return URLEncoder.encode(value, "UTF-8"); }
        catch (UnsupportedEncodingException exception) { return value; }
    }

    private String timezone() {
        String timezone = configRepository.findValue(AuthConstants.CONFIG_TIMEZONE);
        return timezone == null ? "Asia/Shanghai" : timezone;
    }

    private String now() { return TimeUtil.now(timezone()); }
}
