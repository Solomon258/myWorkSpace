package com.icecode.workbench.collect;

/**
 * 从分享链接解析出来的一篇文章的元信息。
 *
 * <p>字段全部可空，调用方必须自己决定降级策略：解析不到标题就没法建笔记（必须报错），
 * 但解析不到课程名或作者仍然可以落盘 —— 只是落点会退化为平台根目录。
 */
public class ArticleMeta {

    /** 平台标识，目前只有 {@code dedao}。 */
    public final String platform;
    /** 原文标题，直接用作 .md 文件名。 */
    public final String title;
    /** 课程 / 专栏名，用作「作者目录」，如 {@code 吴军·教育的方法50讲}。 */
    public final String collection;
    /** 作者 / 主讲人，如 {@code 吴军}。 */
    public final String author;
    /** 原文链接，原样保留（用户点开要用）。 */
    public final String url;
    /** 还原成 Markdown 的正文；抓不到时为空串（此时笔记只剩标题 + 链接）。 */
    public final String content;

    public ArticleMeta(String platform, String title, String collection, String author,
                       String url, String content) {
        this.platform = platform;
        this.title = title == null ? "" : title;
        this.collection = collection == null ? "" : collection;
        this.author = author == null ? "" : author;
        this.url = url;
        this.content = content == null ? "" : content;
    }

    /**
     * 落点目录（相对 Vault）。优先用课程名，因为用户 Vault 里已有的一级目录
     * 就是「作者·课程名」这种形态；课程名缺失时退化为作者名，再退化为平台名。
     */
    public String targetDir() {
        String leaf = !collection.isEmpty() ? collection : (!author.isEmpty() ? author : platform);
        return "知识体系/" + platformLabel() + "/" + leaf;
    }

    public String platformLabel() {
        if ("dedao".equals(platform)) return "得到";
        return platform;
    }
}
