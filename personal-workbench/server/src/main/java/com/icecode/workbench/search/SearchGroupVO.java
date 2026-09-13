package com.icecode.workbench.search;

import java.util.List;

/**
 * 一组同类结果（按实体分组）。
 *
 * <p>{@code total} 是命中总数、{@code items} 是截断后的列表，两者<b>都要给前端</b>：
 * 只给 items 的话，某组被 limit 截断时界面上完全看不出来，
 * 用户会以为「就这几条」—— 这是本项目已经发生过的「静默截断」事故类型。</p>
 */
public class SearchGroupVO {

    private final String type;
    private final String label;
    private final int total;
    private final boolean hasMore;
    private final List<SearchItemVO> items;

    public SearchGroupVO(String type, String label, int total, boolean hasMore, List<SearchItemVO> items) {
        this.type = type;
        this.label = label;
        this.total = total;
        this.hasMore = hasMore;
        this.items = items;
    }

    public String getType() { return type; }
    public String getLabel() { return label; }
    public int getTotal() { return total; }
    /** 本组是否还有未显示的条目（total > items.size()）。前端据此显示「还有 N 条未显示」。 */
    public boolean isHasMore() { return hasMore; }
    public List<SearchItemVO> getItems() { return items; }
}
