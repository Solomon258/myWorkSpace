package com.icecode.workbench.knowledge;

import java.io.UnsupportedEncodingException;
import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

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
 * 知识库问答（轻量 RAG）：Vault 只读索引（按标题分片，指纹缓存）→ CJK 二元组打分检索
 * → 已配置 LLM（DeepSeek 等 OpenAI 兼容接口）则合成答案，否则/失败时降级为摘录式回答；
 * 检索不到足够相关内容时把问题收录为「[待补知识]」进收集箱。
 */
@Service
public class AssistantService {

    private static final Logger LOGGER = LoggerFactory.getLogger(AssistantService.class);
    private static final int MIN_SCORE = 6;
    private static final long CACHE_TTL_MS = 5 * 60 * 1000L;

    private final ObsidianVaultService vaultService;
    private final LlmAnswerClient llmClient;
    private final InboxRepository inboxRepository;
    private final AppConfigRepository configRepository;

    private volatile List<ObsidianVaultService.VaultChunk> cachedChunks;
    private volatile String cachedFingerprint = "";
    private volatile long cachedAt;

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
        List<ObsidianVaultService.VaultChunk> chunks = chunks();
        Set<String> files = new HashSet<String>();
        for (ObsidianVaultService.VaultChunk chunk : chunks) files.add(chunk.file);
        return new IndexStatusVO(true, vaultService.vaultName(), files.size(), chunks.size(), llmClient.isEnabled());
    }

    public AskResponseVO ask(String question) {
        String q = question == null ? "" : question.trim();
        if (q.isEmpty() || q.length() > 500) throw new BizException(ErrorCode.INVALID_PARAMETER, "提问内容不能为空，且不能超过 500 字");
        if (!vaultService.isConfigured()) throw new BizException(ErrorCode.VAULT_NOT_CONFIGURED);

        List<ObsidianVaultService.VaultChunk> chunks = chunks();
        List<String> terms = terms(q);
        ObsidianVaultService.VaultChunk best = null;
        int bestScore = 0;
        List<int[]> scores = new ArrayList<int[]>();
        for (int i = 0; i < chunks.size(); i++) {
            int score = score(chunks.get(i), terms);
            scores.add(new int[]{i, score});
            if (score > bestScore) { bestScore = score; best = chunks.get(i); }
        }

        // 阈值必须随检索词数量缩放：每个词最多贡献 7 分（content 1 + heading 4 + file 2），
        // 短问题只产出 1~2 个 CJK 二元组，理论上限远低于固定的 MIN_SCORE=6，
        // 会被永远判成「不相关」——实测「限流」在库里命中 21 个文件却搜不到。
        int threshold = Math.min(MIN_SCORE, Math.max(1, terms.size()));
        if (best == null || bestScore < threshold) {
            // inbox_item.source 约束仅允许 web/wecom，待补知识按 web 收录
            inboxRepository.insert("[待补知识] " + q, "text", "web", now());
            return new AskResponseVO(false, true, false,
                    "知识库中没有找到足够相关的内容。这条问题本身值得记录——已收录到收集箱，确认后可写入 Vault。",
                    new ArrayList<AskResponseVO.Citation>());
        }

        List<ObsidianVaultService.VaultChunk> used = new ArrayList<ObsidianVaultService.VaultChunk>();
        used.add(best);
        scores.sort((a, b) -> Integer.compare(b[1], a[1]));
        for (int[] entry : scores) {
            if (used.size() >= 3) break;
            ObsidianVaultService.VaultChunk candidate = chunks.get(entry[0]);
            if (candidate == best) continue;
            if (entry[1] >= Math.max(MIN_SCORE, bestScore / 2)) used.add(candidate);
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
        for (ObsidianVaultService.VaultChunk chunk : used) {
            String snippet = chunk.content.length() <= 90 ? chunk.content : chunk.content.substring(0, 90) + "…";
            citations.add(new AskResponseVO.Citation(chunk.file, chunk.heading, snippet,
                    "obsidian://open?vault=" + urlEncode(vaultService.vaultName()) + "&file=" + urlEncode(chunk.file)));
        }
        return new AskResponseVO(true, false, llmUsed, answer, citations);
    }

    /** 索引缓存：指纹（文件数+最新 mtime）变化或超过 5 分钟才重扫。 */
    private List<ObsidianVaultService.VaultChunk> chunks() {
        String fingerprint = vaultService.indexFingerprint();
        List<ObsidianVaultService.VaultChunk> current = cachedChunks;
        if (current != null && fingerprint.equals(cachedFingerprint)
                && System.currentTimeMillis() - cachedAt < CACHE_TTL_MS) {
            return current;
        }
        synchronized (this) {
            current = vaultService.readChunks();
            cachedChunks = current;
            cachedFingerprint = fingerprint;
            cachedAt = System.currentTimeMillis();
            return current;
        }
    }

    /** 检索词：CJK 二元组 + 拉丁词（长度≥2）。单字问题退回单字匹配。 */
    private List<String> terms(String question) {
        List<String> terms = new ArrayList<String>();
        String cleaned = question.replaceAll("[？?，。！!、：:；;（）()【】\\[\\]\"'“”‘’]", " ");
        StringBuilder cjkRun = new StringBuilder();
        for (String token : cleaned.split("\\s+")) {
            if (token.isEmpty()) continue;
            if (token.matches(".*[一-龥].*")) {
                for (char ch : token.toCharArray()) {
                    if (ch >= 0x4E00 && ch <= 0x9FFF) cjkRun.append(ch);
                    else {
                        emitCjk(terms, cjkRun);
                        if (Character.isLetterOrDigit(ch)) cjkRun.append(ch);
                    }
                }
                emitCjk(terms, cjkRun);
            } else if (token.length() >= 2) {
                terms.add(token.toLowerCase());
            }
        }
        return terms;
    }

    private void emitCjk(List<String> terms, StringBuilder run) {
        String text = run.toString();
        if (text.length() == 1) terms.add(text);
        for (int i = 0; i + 2 <= text.length(); i++) terms.add(text.substring(i, i + 2));
        run.setLength(0);
    }

    private int score(ObsidianVaultService.VaultChunk chunk, List<String> terms) {
        String heading = chunk.heading.toLowerCase();
        String file = chunk.file.toLowerCase();
        String content = chunk.content.toLowerCase();
        int score = 0;
        for (String term : terms) {
            String t = term.toLowerCase();
            if (content.contains(t)) score += 1;
            if (heading.contains(t)) score += 4;
            if (file.contains(t)) score += 2;
        }
        return score;
    }

    private String synthesize(String question, List<ObsidianVaultService.VaultChunk> used) throws Exception {
        StringBuilder context = new StringBuilder();
        for (int i = 0; i < used.size(); i++) {
            ObsidianVaultService.VaultChunk chunk = used.get(i);
            context.append("【笔记").append(i + 1).append("：").append(chunk.file)
                    .append(" · ").append(chunk.heading).append("】\n")
                    .append(chunk.content.length() <= 600 ? chunk.content : chunk.content.substring(0, 600))
                    .append("\n\n");
        }
        String system = "你是个人知识库助手。只根据给定的笔记片段回答用户问题，用中文，简洁（150字以内）。"
                + "片段不足以回答时直说“笔记里相关信息有限”，不要编造。不要复述问题，不要自行添加出处列表。";
        String user = "问题：" + question + "\n\n" + context;
        return llmClient.chat(system, user);
    }

    private String extractive(List<ObsidianVaultService.VaultChunk> used) {
        String content = used.get(0).content;
        String snippet = content.length() <= 140 ? content : content.substring(0, 140) + "…";
        return "根据你的笔记，相关内容是：" + snippet;
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
