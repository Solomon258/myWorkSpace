package com.icecode.workbench.task;

import java.util.List;

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

    /**
     * 工作 / 生活分组。留空或填 {@code auto} 时由后端按关键词自动判定
     * （复用 {@code FavoriteService.autoGroup} 的规则），与收藏页的「空间」是同一套语义。
     */
    @Pattern(regexp = "^(auto|work|life)?$", message = "分组只能填 work / life / auto，留空表示自动判定")
    private String grp;

    @Size(max = 1000, message = "备注不能超过 1000 字")
    private String note;

    /**
     * 要挂到这条任务上的附件 id（先在 {@code POST /api/v1/attachments} 上传拿到 id）。
     * 留空或 null = 没有附件，行为与加这个字段之前**完全一致**。
     */
    @Size(max = 9, message = "一条记录最多带 9 个附件")
    private List<Long> attachmentIds;

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
    public String getGrp() { return grp; }
    public void setGrp(String grp) { this.grp = grp; }
    public String getNote() { return note; }
    public void setNote(String note) { this.note = note; }
    public List<Long> getAttachmentIds() { return attachmentIds; }
    public void setAttachmentIds(List<Long> attachmentIds) { this.attachmentIds = attachmentIds; }
}
