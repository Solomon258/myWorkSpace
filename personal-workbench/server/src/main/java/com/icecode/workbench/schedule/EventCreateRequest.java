package com.icecode.workbench.schedule;

import javax.validation.constraints.Max;
import javax.validation.constraints.Min;
import javax.validation.constraints.NotBlank;
import javax.validation.constraints.Pattern;
import javax.validation.constraints.Size;

public class EventCreateRequest {

    @NotBlank(message = "日程标题不能为空")
    @Size(max = 200, message = "日程标题不能超过 200 字")
    private String title;

    @Pattern(regexp = "meeting|deep_block|other", message = "日程类型只能填 会议(meeting) / 深度块(deep_block) / 其他(other)")
    private String type = "other";

    @Pattern(regexp = "^$|\\d{4}-\\d{2}-\\d{2}", message = "日期格式不正确，请按 YYYY-MM-DD 填写；留空表示时间待定")
    private String date;

    @Pattern(regexp = "^$|([01]\\d|2[0-3]):[0-5]\\d", message = "开始时间格式不正确，请按 HH:mm 填写，例如 09:30")
    private String start;

    @Pattern(regexp = "^$|([01]\\d|2[0-3]):[0-5]\\d", message = "结束时间格式不正确，请按 HH:mm 填写，例如 10:00")
    private String end;

    /**
     * 重复期数（「每周三做什么」这类周期安排）：1 = 不重复（默认），4 = 连续 4 周。
     *
     * <p>用包装类型 {@code Integer} 而不是 {@code int}：字段缺省时（老前端、curl 调用）
     * 会落到 null 并被当成「不重复」；写成 int 的话 Java 默认值 0 会直接被 {@code @Min(1)}
     * 判成非法参数，把「没传这个字段」变成一次 400。</p>
     */
    @Min(value = 1, message = "重复期数最小是 1（1 = 不重复）")
    @Max(value = 12, message = "重复期数最多 12 期")
    private Integer repeatWeeks;

    public String getTitle() { return title; }
    public void setTitle(String title) { this.title = title; }
    public String getType() { return type; }
    public void setType(String type) { this.type = type; }
    public String getDate() { return date; }
    public void setDate(String date) { this.date = date; }
    public String getStart() { return start; }
    public void setStart(String start) { this.start = start; }
    public String getEnd() { return end; }
    public void setEnd(String end) { this.end = end; }
    public Integer getRepeatWeeks() { return repeatWeeks; }
    public void setRepeatWeeks(Integer repeatWeeks) { this.repeatWeeks = repeatWeeks; }
}
