package com.icecode.workbench.dashboard;

public class DashboardTaskVO {
    private final long id;
    private final String title;
    private final String priority;
    private final String status;
    private final String due;
    private final boolean overdue;
    private final int overdueDays;
    private final boolean deep;
    private final boolean blocking;
    private final int postponed;
    private final String reason;

    public DashboardTaskVO(long id, String title, String priority, String status, String due,
                           boolean overdue, int overdueDays, boolean deep, boolean blocking,
                           int postponed, String reason) {
        this.id = id;
        this.title = title;
        this.priority = priority;
        this.status = status;
        this.due = due;
        this.overdue = overdue;
        this.overdueDays = overdueDays;
        this.deep = deep;
        this.blocking = blocking;
        this.postponed = postponed;
        this.reason = reason;
    }

    public long getId() { return id; }
    public String getTitle() { return title; }
    public String getPriority() { return priority; }
    public String getStatus() { return status; }
    public String getDue() { return due; }
    public boolean isOverdue() { return overdue; }
    public int getOverdueDays() { return overdueDays; }
    public boolean isDeep() { return deep; }
    public boolean isBlocking() { return blocking; }
    public int getPostponed() { return postponed; }
    public String getReason() { return reason; }
}
