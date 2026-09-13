package com.icecode.workbench.task;

import javax.validation.constraints.NotBlank;
import javax.validation.constraints.Pattern;
import javax.validation.constraints.Size;

public class TaskCreateRequest {

    @NotBlank(message = "任务标题不能为空")
    @Size(max = 200, message = "任务标题不能超过 200 字")
    private String title;

    @Size(max = 2000, message = "任务描述不能超过 2000 字")
    private String description;

    @Pattern(regexp = "P[0-3]", message = "优先级只能填 P0 / P1 / P2 / P3")
    private String priority = "P2";

    @Pattern(regexp = "^$|\\d{4}-\\d{2}-\\d{2}", message = "日期格式不正确，请按 YYYY-MM-DD 填写")
    private String due;

    private boolean deep;
    private boolean blocking;

    @Size(max = 1000, message = "备注不能超过 1000 字")
    private String note;

    public String getTitle() { return title; }
    public void setTitle(String title) { this.title = title; }
    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }
    public String getPriority() { return priority; }
    public void setPriority(String priority) { this.priority = priority; }
    public String getDue() { return due; }
    public void setDue(String due) { this.due = due; }
    public boolean isDeep() { return deep; }
    public void setDeep(boolean deep) { this.deep = deep; }
    public boolean isBlocking() { return blocking; }
    public void setBlocking(boolean blocking) { this.blocking = blocking; }
    public String getNote() { return note; }
    public void setNote(String note) { this.note = note; }
}
