package com.icecode.workbench.inbox;

public class ClassifyPayload {
    private String title;
    private String due;
    private String priority;
    private String start;
    private String end;
    private String eventType;
    /**
     * 重复期数（「每周三做什么」这类周期安排）：1 或 null = 不重复，4 = 连续 4 周。
     * 只对 {@code category = schedule} 有意义；任务 / 收藏即使被填了也会被忽略
     * （确认生成时只读它来建日程，见 InboxService.createEvent）。
     */
    private Integer repeatWeeks;

    public String getTitle() { return title; }
    public void setTitle(String title) { this.title = title; }
    public String getDue() { return due; }
    public void setDue(String due) { this.due = due; }
    public String getPriority() { return priority; }
    public void setPriority(String priority) { this.priority = priority; }
    public String getStart() { return start; }
    public void setStart(String start) { this.start = start; }
    public String getEnd() { return end; }
    public void setEnd(String end) { this.end = end; }
    public String getEventType() { return eventType; }
    public void setEventType(String eventType) { this.eventType = eventType; }
    public Integer getRepeatWeeks() { return repeatWeeks; }
    public void setRepeatWeeks(Integer repeatWeeks) { this.repeatWeeks = repeatWeeks; }
}
