package com.icecode.workbench.task;

public class TaskVO {

    private final long id;
    private final String title;
    private final String description;
    private final String priority;
    private final String status;
    private final String due;
    private final boolean overdue;
    private final int overdueDays;
    private final boolean deep;
    private final boolean blocking;
    private final String note;
    private final String grp;
    private final int postponed;
    private final Long sourceInboxId;
    private final String createdAt;
    private final String updatedAt;
    private final String completedAt;
    private final boolean demo;

    public TaskVO(long id, String title, String description, String priority, String status, String due,
                  boolean overdue, int overdueDays, boolean deep, boolean blocking, String note,
                  String grp, int postponed, Long sourceInboxId, String createdAt, String updatedAt,
                  String completedAt, boolean demo) {
        this.id = id;
        this.title = title;
        this.description = description;
        this.priority = priority;
        this.status = status;
        this.due = due;
        this.overdue = overdue;
        this.overdueDays = overdueDays;
        this.deep = deep;
        this.blocking = blocking;
        this.note = note;
        this.grp = grp;
        this.postponed = postponed;
        this.sourceInboxId = sourceInboxId;
        this.createdAt = createdAt;
        this.updatedAt = updatedAt;
        this.completedAt = completedAt;
        this.demo = demo;
    }

    public long getId() { return id; }
    public String getTitle() { return title; }
    public String getDescription() { return description; }
    public String getPriority() { return priority; }
    public String getStatus() { return status; }
    public String getDue() { return due; }
    public boolean isOverdue() { return overdue; }
    public int getOverdueDays() { return overdueDays; }
    public boolean isDeep() { return deep; }
    public boolean isBlocking() { return blocking; }
    public String getNote() { return note; }
    public String getGrp() { return grp; }
    public int getPostponed() { return postponed; }
    public Long getSourceInboxId() { return sourceInboxId; }
    public String getCreatedAt() { return createdAt; }
    public String getUpdatedAt() { return updatedAt; }
    public String getCompletedAt() { return completedAt; }
    public boolean isDemo() { return demo; }
}
