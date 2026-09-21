package com.icecode.workbench.search;

/**
 * 一条搜索结果的落点：点开之后去哪个页签、以及定位到那一条需要的附加条件。
 *
 * <p>这个对象是「搜索 → 定位」的唯一依据，前端只按 {@code tab} 分发，
 * 不允许在渲染结果行时各自内联跳转逻辑 —— 那样最容易漏掉「先切日期再渲染」这类顺序要求。</p>
 *
 * <p>两个字段的 {@code null} 是<b>有语义的</b>，不是缺省：日程的 {@code date} 为 null
 * 表示这条日程属于「待定时间」（US-4.2），前端要滚到待定区，
 * <b>任何方向都不许隐式补成今天</b>。收藏的 {@code grp} 决定先切到哪个空间，
 * 空间不对的话目标行根本不在 DOM 里，表现为「点了没反应」。</p>
 */
public class SearchTargetVO {

    private final String tab;
    private final String date;
    private final String grp;
    private final String day;

    public SearchTargetVO(String tab, String date, String grp, String day) {
        this.tab = tab;
        this.date = date;
        this.grp = grp;
        this.day = day;
    }

    public static SearchTargetVO tab(String tab) {
        return new SearchTargetVO(tab, null, null, null);
    }

    public static SearchTargetVO event(String date) {
        return new SearchTargetVO("schedule", date, null, null);
    }

    public static SearchTargetVO favorite(String grp) {
        return new SearchTargetVO("favorites", null, grp, null);
    }

    public static SearchTargetVO timeline(String day) {
        return new SearchTargetVO("timeline", null, null, day);
    }

    public String getTab() { return tab; }
    public String getDate() { return date; }
    public String getGrp() { return grp; }
    public String getDay() { return day; }
}
