package com.icecode.workbench.dashboard;

public class DashboardMetricsVO {
    private final int taskDone;
    private final int taskTotal;
    private final int taskOpen;
    private final int overdue;
    private final int eventToday;
    private final int inboxPending;
    private final int pomoToday;

    public DashboardMetricsVO(int taskDone, int taskTotal, int taskOpen, int overdue, int eventToday,
                              int inboxPending, int pomoToday) {
        this.taskDone = taskDone;
        this.taskTotal = taskTotal;
        this.taskOpen = taskOpen;
        this.overdue = overdue;
        this.eventToday = eventToday;
        this.inboxPending = inboxPending;
        this.pomoToday = pomoToday;
    }

    public int getTaskDone() { return taskDone; }
    public int getTaskTotal() { return taskTotal; }
    public int getTaskOpen() { return taskOpen; }
    public int getOverdue() { return overdue; }
    public int getEventToday() { return eventToday; }
    public int getInboxPending() { return inboxPending; }
    public int getPomoToday() { return pomoToday; }
}
