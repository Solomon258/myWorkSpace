package com.icecode.workbench.task;

/**
 * 「已完成」页里某一天的汇总（2026-09-20）。见 {@link TaskDoneSummaryVO} 的口径说明。
 *
 * <p>字段刻意保持「前端拿来就能拼」的程度：日期用 {@code yyyy-MM-dd} 文本（与
 * {@code dashboard.date} 同一口径），时间用 {@code HH:mm} 文本 —— 前端不解析、
 * 不重算，避免「页面上的今天」和「后端认的今天」变成两套口径。</p>
 */
public class TaskDoneDayVO {

    private final String date;
    private final int count;
    private final int p0Count;
    private final int p1Count;
    private final int deepCount;
    private final String firstAt;
    private final String lastAt;
    private final int canceledCount;
    /** 与前一天相比的完成数差（今天独有；负数 = 比昨天少）。其它天为 0。 */
    private final int diffFromPreviousDay;

    public TaskDoneDayVO(String date, int count, int p0Count, int p1Count, int deepCount,
                         String firstAt, String lastAt, int canceledCount, int diffFromPreviousDay) {
        this.date = date;
        this.count = count;
        this.p0Count = p0Count;
        this.p1Count = p1Count;
        this.deepCount = deepCount;
        this.firstAt = firstAt;
        this.lastAt = lastAt;
        this.canceledCount = canceledCount;
        this.diffFromPreviousDay = diffFromPreviousDay;
    }

    public String getDate() { return date; }
    public int getCount() { return count; }
    public int getP0Count() { return p0Count; }
    public int getP1Count() { return p1Count; }
    public int getDeepCount() { return deepCount; }
    /** 当天最早的完成时刻（HH:mm），没有完成为 null。 */
    public String getFirstAt() { return firstAt; }
    /** 当天最晚的完成时刻（HH:mm），没有完成为 null。 */
    public String getLastAt() { return lastAt; }
    /**
     * 当天取消的条数。**不并入 count** —— 用户要的是「完成了什么」，
     * 取消不是成果；但也不能藏掉（藏掉的话那条任务在界面上连痕迹都没有）。
     */
    public int getCanceledCount() { return canceledCount; }
    public int getDiffFromPreviousDay() { return diffFromPreviousDay; }
}
