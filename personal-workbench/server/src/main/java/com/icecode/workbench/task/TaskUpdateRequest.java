package com.icecode.workbench.task;

import javax.validation.constraints.Pattern;
import javax.validation.constraints.Size;

public class TaskUpdateRequest {

    @Size(min = 1, max = 200, message = "任务标题不能为空，且不能超过 200 字")
    private String title;

    @Size(max = 2000, message = "任务描述不能超过 2000 字")
    private String description;

    @Pattern(regexp = "P[0-3]", message = "优先级只能填 P0 / P1 / P2 / P3")
    private String priority;

    @Pattern(regexp = "^$|\\d{4}-\\d{2}-\\d{2}", message = "日期格式不正确，请按 YYYY-MM-DD 填写；留空表示无截止")
    private String due;

    private Boolean deep;
    private Boolean blocking;

    /**
     * 工作 / 生活分组（V9）。留空表示「不改这一项」—— 注意这与创建时的
     * 「留空 = 自动判定」语义不同：编辑一条已有任务时，用户没动下拉就不该把
     * 原来的分组冲掉。要显式改成某一侧就传 work / life。
     */
    @Pattern(regexp = "^(work|life)?$", message = "分组只能填 work 或 life")
    private String grp;

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
    public Boolean getDeep() { return deep; }
    public void setDeep(Boolean deep) { this.deep = deep; }
    public Boolean getBlocking() { return blocking; }
    public void setBlocking(Boolean blocking) { this.blocking = blocking; }
    public String getGrp() { return grp; }
    public void setGrp(String grp) { this.grp = grp; }
    public String getNote() { return note; }
    public void setNote(String note) { this.note = note; }
}
