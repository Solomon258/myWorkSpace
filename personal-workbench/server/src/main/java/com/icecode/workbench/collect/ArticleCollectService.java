package com.icecode.workbench.collect;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.springframework.stereotype.Service;

import com.icecode.workbench.common.BizException;
import com.icecode.workbench.common.ErrorCode;
import com.icecode.workbench.knowledge.KnowledgeNoteVO;
import com.icecode.workbench.knowledge.KnowledgeService;

/**
 * 把一条「分享链接 / 分享文案」变成 Vault 里的笔记 + 工作台知识库记录。
 *
 * <p>这是<b>所有入口的公共落盘能力</b>：手动粘贴、微信回调、云服务器中转都调这里，
 * 所以「解析 → 决定归档目录 → 写 Vault → 落 knowledge_note」永远只有一份实现。
 * 换入口不需要碰这段逻辑，这正是先做它的理由。
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

    public ArticleCollectService(DedaoShareParser dedaoShareParser, KnowledgeService knowledgeService) {
        this.dedaoShareParser = dedaoShareParser;
        this.knowledgeService = knowledgeService;
    }

    public CollectResultVO collect(String raw) {
        String link = extractUrl(raw);
        if (link.isEmpty()) {
            throw new BizException(ErrorCode.INVALID_PARAMETER,
                    "没找到链接，请把文章链接或微信里的分享文案粘贴进来");
        }
        ArticleMeta meta;
        if (dedaoShareParser.supports(link)) {
            meta = dedaoShareParser.parse(link);
        } else {
            throw new BizException(ErrorCode.UNSUPPORTED_LINK);
        }
        KnowledgeNoteVO note = knowledgeService.createArticle(null, meta.targetDir(),
                meta.title, meta.content, buildTags(meta), meta.url);
        return new CollectResultVO(note.getId(), meta, note.getVaultPath(), note.getSyncStatus());
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
