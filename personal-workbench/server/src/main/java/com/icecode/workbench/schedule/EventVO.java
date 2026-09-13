package com.icecode.workbench.schedule;

public class EventVO {
    private final long id;
    private final String title;
    private final String type;
    private final String date;
    private final String start;
    private final String end;
    private final Long sourceInboxId;
    private final String createdAt;
    private final boolean demo;
    /**
     * 同一条重复日程的组标识（= 组内首条的 id）；不重复的日程为 null。
     * 「每周三开周会」这类周期安排是**物化**成 N 条独立记录的（见 V8 迁移），
     * 前端靠这两个字段显示「每周 · 共 4 次」的标注，并让用户知道改其中一条只影响那一条。
     */
    private final Long repeatGroup;
    /** 该组共几期（不重复 = 1）。 */
    private final int repeatTotal;

    public EventVO(long id, String title, String type, String date, String start, String end,
                   Long sourceInboxId, String createdAt, boolean demo, Long repeatGroup, int repeatTotal) {
        this.id = id;
        this.title = title;
        this.type = type;
        this.date = date;
        this.start = start;
        this.end = end;
        this.sourceInboxId = sourceInboxId;
        this.createdAt = createdAt;
        this.demo = demo;
        this.repeatGroup = repeatGroup;
        this.repeatTotal = repeatTotal;
    }

    public long getId() { return id; }
    public String getTitle() { return title; }
    public String getType() { return type; }
    public String getDate() { return date; }
    public String getStart() { return start; }
    public String getEnd() { return end; }
    public Long getSourceInboxId() { return sourceInboxId; }
    public String getCreatedAt() { return createdAt; }
    public boolean isDemo() { return demo; }
    public Long getRepeatGroup() { return repeatGroup; }
    public int getRepeatTotal() { return repeatTotal; }
}
