package com.icecode.workbench.task;

class TaskRecord {
    long id;
    String title;
    String description;
    String priority;
    String status;
    String due;
    boolean deep;
    boolean blocking;
    String note;
    /** 工作 / 生活分组（V9）。取值只有 work / life，由 TaskService.normalizeGroup 守。 */
    String grp;
    int postponed;
    Long sourceInboxId;
    String completedAt;
    String createdAt;
    String updatedAt;
    boolean demo;
}
