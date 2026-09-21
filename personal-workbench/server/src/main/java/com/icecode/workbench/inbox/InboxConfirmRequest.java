package com.icecode.workbench.inbox;

import javax.validation.constraints.Max;
import javax.validation.constraints.Min;
import javax.validation.constraints.NotBlank;
import javax.validation.constraints.Pattern;
import javax.validation.constraints.Size;

public class InboxConfirmRequest {

    @NotBlank(message = "分类不能为空")
    @Pattern(regexp = "task|schedule|favorite|knowledge", message = "分类只能填 任务(task) / 日程(schedule) / 收藏(favorite) / 知识(knowledge)")
    private String category;

    @Size(max = 200, message = "标题不能超过 200 字")
    private String title;

    @Pattern(regexp = "^$|\\d{4}-\\d{2}-\\d{2}", message = "日期格式不正确，请按 YYYY-MM-DD 填写")
    private String due;

    @Pattern(regexp = "P[0-3]", message = "优先级只能填 P0 / P1 / P2 / P3")
    private String priority;

    // 整理页的「开始时间」是自由文本框，用户很容易输成 9:00 / 9点。
    // 这里必须写清期望格式，否则前端 toast 会把正则 ^$|([01]\d|2[0-3]):[0-5]\d 原样甩给用户。
    @Pattern(regexp = "^$|([01]\\d|2[0-3]):[0-5]\\d", message = "开始时间格式不正确，请按 HH:mm 填写，例如 09:30")
    private String start;

    @Pattern(regexp = "^$|([01]\\d|2[0-3]):[0-5]\\d", message = "结束时间格式不正确，请按 HH:mm 填写，例如 10:00")
    private String end;

    @Pattern(regexp = "meeting|deep_block|other", message = "日程类型只能填 会议(meeting) / 深度块(deep_block) / 其他(other)")
    private String eventType;

    /**
     * 重复期数（「每周三做什么」这类周期安排）：1 或 null = 不重复，4 = 连续 4 周。
     * 整理器识别出「每周 X」时会预填 4，用户在确认卡上看到的也是这个值。
     * 同 {@code EventCreateRequest.repeatWeeks}，用包装类型以便「没传」等同于「不重复」。
     */
    @Min(value = 1, message = "重复期数最小是 1（1 = 不重复）")
    @Max(value = 12, message = "重复期数最多 12 期")
    private Integer repeatWeeks;

    public String getCategory() { return category; }
    public void setCategory(String category) { this.category = category; }
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
