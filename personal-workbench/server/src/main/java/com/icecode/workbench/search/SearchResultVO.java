package com.icecode.workbench.search;

import java.util.List;

/**
 * 一次搜索的完整结果。
 *
 * <p>{@code expandedTerms} 与 {@code semanticState} 是给「两段式渲染」用的：
 * 关键字结果先渲染（{@code semanticState = pending}），联想补位后再刷新成 {@code ready}。
 * P0 阶段恒为 {@code off}（未启用语义），字段先占位是为了让前后端契约一次定好，
 * 免得 P1 再改一次接口。</p>
 */
public class SearchResultVO {

    /** 语义联想的状态：未启用 / 计算中 / 已完成 / 失败（失败时前端照旧显示关键字结果，不报错）。 */
    public static final String SEMANTIC_OFF = "off";
    public static final String SEMANTIC_PENDING = "pending";
    public static final String SEMANTIC_READY = "ready";
    public static final String SEMANTIC_FAILED = "failed";

    private final String query;
    private final List<String> expandedTerms;
    private final String semanticState;
    private final long elapsedMs;
    private final boolean truncated;
    private final int totalCount;
    private final List<SearchGroupVO> groups;

    public SearchResultVO(String query, List<String> expandedTerms, String semanticState, long elapsedMs,
                          boolean truncated, int totalCount, List<SearchGroupVO> groups) {
        this.query = query;
        this.expandedTerms = expandedTerms;
        this.semanticState = semanticState;
        this.elapsedMs = elapsedMs;
        this.truncated = truncated;
        this.totalCount = totalCount;
        this.groups = groups;
    }

    public String getQuery() { return query; }
    public List<String> getExpandedTerms() { return expandedTerms; }
    public String getSemanticState() { return semanticState; }
    public long getElapsedMs() { return elapsedMs; }
    /** 是否有任意一组被 limit 截断（前端据此提示「仅显示前 N 条」）。 */
    public boolean isTruncated() { return truncated; }
    public int getTotalCount() { return totalCount; }
    public List<SearchGroupVO> getGroups() { return groups; }
}
