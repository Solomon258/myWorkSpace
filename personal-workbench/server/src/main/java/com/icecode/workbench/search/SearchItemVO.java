package com.icecode.workbench.search;

import java.util.List;
import java.util.Map;

/**
 * 一条搜索结果。
 *
 * <p>{@code entity} 与 {@code target.tab} 是<b>两件事</b>，不要合并：
 * {@code entity} 说明这条数据本身是什么（决定图标与配色），
 * {@code target.tab} 说明点开之后去哪儿。回收站里的条目最典型 ——
 * 它可能是一条任务（{@code entity = task}，界面显示「任」和蓝色），
 * 但 {@code target.tab = trash}，因为任务页查的是 {@code deleted=0}，跳过去根本找不到它。</p>
 */
public class SearchItemVO {

    /** 匹配强度：整串命中 / 分词命中 / 语义联想命中。 */
    public static final String KIND_EXACT = "exact";
    public static final String KIND_SEMI = "semi";
    public static final String KIND_SEMANTIC = "semantic";

    /** 命中位置。前端用它显示「位于标题」这类说明，让用户知道为什么这条会出现。 */
    public static final String FIELD_TITLE = "title";
    public static final String FIELD_TAGS = "tags";
    public static final String FIELD_BODY = "body";
    public static final String FIELD_RAW = "raw";
    public static final String FIELD_LOG = "log";

    private final String entity;
    private final long id;
    private final String title;
    private final String snippet;
    private final List<String> matchedTerms;
    private final String matchKind;
    private final String matchField;
    private final boolean deleted;
    private final Map<String, Object> meta;
    private final String updatedAt;
    private final SearchTargetVO target;

    public SearchItemVO(String entity, long id, String title, String snippet, List<String> matchedTerms,
                        String matchKind, String matchField, boolean deleted, Map<String, Object> meta,
                        String updatedAt, SearchTargetVO target) {
        this.entity = entity;
        this.id = id;
        this.title = title;
        this.snippet = snippet;
        this.matchedTerms = matchedTerms;
        this.matchKind = matchKind;
        this.matchField = matchField;
        this.deleted = deleted;
        this.meta = meta;
        this.updatedAt = updatedAt;
        this.target = target;
    }

    public String getEntity() { return entity; }
    public long getId() { return id; }
    public String getTitle() { return title; }
    public String getSnippet() { return snippet; }
    /** 命中的检索词，按长度降序 —— 前端按这个顺序做高亮，长词优先，避免短词把长词切碎。 */
    public List<String> getMatchedTerms() { return matchedTerms; }
    public String getMatchKind() { return matchKind; }
    public String getMatchField() { return matchField; }
    public boolean isDeleted() { return deleted; }
    public Map<String, Object> getMeta() { return meta; }
    public String getUpdatedAt() { return updatedAt; }
    public SearchTargetVO getTarget() { return target; }
}
