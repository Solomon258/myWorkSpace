package com.icecode.workbench.collect;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.springframework.stereotype.Service;

import com.icecode.workbench.auth.AppConfigRepository;
import com.icecode.workbench.common.BizException;
import com.icecode.workbench.common.ErrorCode;
import com.icecode.workbench.knowledge.KnowledgeNoteVO;
import com.icecode.workbench.knowledge.KnowledgeService;
import com.icecode.workbench.settings.SettingsService;

/**
 * 把一条「分享链接 / 分享文案」变成 Vault 里的笔记 + 工作台知识库记录。
 *
 * <p>这是<b>所有入口的公共落盘能力</b>：手动粘贴、微信回调、云服务器中转都调这里，
 * 所以「解析 → 决定归档目录 → 写 Vault → 落 knowledge_note」永远只有一份实现。
 * 换入口不需要碰这段逻辑，这正是先做它的理由。
 *
 * <p>2026-09-20 起多了一道闸门：解析结果标记为「只有试读」时<b>拒绝写入</b>（见
 * {@link #trialOnlyMessage}）。原因是得到的分享页对匿名请求只下发约 20% 正文，
 * 而旧实现会照单全收 —— 用户在 Obsidian 里看到的是一篇「看不出缺了一半」的笔记。
 * 配上得到登录 Cookie 后即可取到全文（自己的已购内容）。
 */
@Service
public class ArticleCollectService {

    /**
     * 从分享文案里抠出链接。
     *
     * <p>微信里的分享常常是一整段话（「我分享给你一个知识红包 https://… 复制此链接打开得到」），
     * 用户不会替我们只复制链接，所以必须自己抽。遇到空白或中文标点即停止 ——
     * 中文文案里链接后面紧跟中文标点的情况非常普遍，不挡掉会把标点吃进 URL。
     */
    private static final Pattern URL_PATTERN =
            Pattern.compile("https?://[^\\s，。；、！？\"'（）【】《》]+");

    private final DedaoShareParser dedaoShareParser;
    private final KnowledgeService knowledgeService;
    private final AppConfigRepository configRepository;

    public ArticleCollectService(DedaoShareParser dedaoShareParser, KnowledgeService knowledgeService,
                                 AppConfigRepository configRepository) {
        this.dedaoShareParser = dedaoShareParser;
        this.knowledgeService = knowledgeService;
        this.configRepository = configRepository;
    }

    public CollectResultVO collect(String raw) {
        String link = extractUrl(raw);
        if (link.isEmpty()) {
            throw new BizException(ErrorCode.INVALID_PARAMETER,
                    "没找到链接，请把文章链接或微信里的分享文案粘贴进来");
        }
        ArticleMeta meta;
        // 得到分享页对匿名请求只给试读正文，所以抓取时要带上用户自己的登录态。
        // 没配 Cookie 时照样发请求 —— 这样普通可读的文章（服务端已给权限）仍然能收，
        // 不能因为「没配凭据」就把整个功能挡在门外。
        String cookie = null;
        if (dedaoShareParser.supports(link)) {
            cookie = configRepository.findValue(SettingsService.KEY_DEDAO_COOKIE);
            meta = dedaoShareParser.parse(link, cookie);
        } else {
            throw new BizException(ErrorCode.UNSUPPORTED_LINK);
        }
        if (meta.trialOnly) throw new BizException(ErrorCode.ARTICLE_TRIAL_ONLY, trialOnlyMessage(cookie));
        KnowledgeNoteVO note = knowledgeService.createArticle(null, meta.targetDir(),
                meta.title, meta.content, buildTags(meta), meta.url);
        return new CollectResultVO(note.getId(), meta, note.getVaultPath(), note.getSyncStatus());
    }

    /**
     * 只拿到试读时的处理：<b>拒绝落盘</b>，并把「为什么」和「下一步做什么」讲清楚。
     *
     * <p>为什么宁可这次失败、也不写入半篇：笔记一旦进了 Vault，看起来就是一篇完整的文档，
     * 用户不会再回头核对它为什么短 —— 到时候问题只会以「知识库问答答得不全」的形式浮出来，
     * 更难追溯。而且旧笔记不会自动更新，先前存下的那半篇只能手工重收。
     */
    private String trialOnlyMessage(String cookie) {
        if (cookie == null || cookie.trim().isEmpty()) {
            return "这篇是得到的试读分享，只开放了约 20% 正文（标题、作者、课程都读到了，但正文不全）。"
                    + "为避免往 Obsidian 里写入半篇文章，这次没有收藏。"
                    + "请到「设置 → 得到登录 Cookie」粘贴浏览器里的 Cookie（你已购买的文章会取到全文）后重试。";
        }
        return "已带上登录 Cookie，但得到这次仍然只返回试读正文，所以没有收藏。"
                + "通常是 Cookie 失效了：重新登录得到后复制一份新的 Cookie 再试；"
                + "若刚更新过，则这篇可能不在该账号的已购 / 已领取范围内。";
    }

    static String extractUrl(String raw) {
        if (raw == null) return "";
        Matcher matcher = URL_PATTERN.matcher(raw);
        return matcher.find() ? matcher.group().trim() : "";
    }

    /**
     * 标签按「作者 → 课程 → 平台」排列，对齐用户 Vault 里既有笔记的 tags 习惯
     * （如 {@code 吴军 / 得到}）。课程名里的「作者·」前缀要去掉，否则和作者标签重复。
     */
    private List<String> buildTags(ArticleMeta meta) {
        List<String> tags = new ArrayList<String>();
        if (!meta.author.isEmpty()) tags.add(meta.author);
        String course = meta.collection;
        int dot = course.indexOf('·');
        if (dot > 0 && dot < course.length() - 1) course = course.substring(dot + 1);
        if (!course.isEmpty() && !tags.contains(course)) tags.add(course);
        String platform = meta.platformLabel();
        if (!platform.isEmpty() && !tags.contains(platform)) tags.add(platform);
        return tags;
    }
}
