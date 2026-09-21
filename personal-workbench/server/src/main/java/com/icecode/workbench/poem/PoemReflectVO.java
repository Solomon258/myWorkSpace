package com.icecode.workbench.poem;

/**
 * 一首诗的 AI 品读结果 —— 两段：意境字句解析 + 与当下生活的联结。
 *
 * <p>刻意只有两个正文字段，不再往下拆「关键词数组 / 要点列表」：产品要的是
 * **读起来像朋友轻声诉说的一段话**，拆成条目就会把它拉回学术腔。
 * {@code cached} 只用于前端提示（「已为你保存过」），不影响渲染结构。
 */
public class PoemReflectVO {

    /** 第一段：意境与关键字句的解析。 */
    private final String imagery;
    /** 第二段：与当下生活的联结（生活指导 / 情感共鸣 / 人生感悟）。 */
    private final String affinity;
    /** 是否命中服务端缓存（同一首诗第二次进入全屏即为 true）。 */
    private final boolean cached;

    public PoemReflectVO(String imagery, String affinity, boolean cached) {
        this.imagery = imagery;
        this.affinity = affinity;
        this.cached = cached;
    }

    public String getImagery() { return imagery; }

    public String getAffinity() { return affinity; }

    public boolean isCached() { return cached; }
}
