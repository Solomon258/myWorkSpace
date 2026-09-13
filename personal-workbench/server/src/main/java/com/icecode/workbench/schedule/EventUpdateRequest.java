package com.icecode.workbench.schedule;

import javax.validation.constraints.Pattern;
import javax.validation.constraints.Size;

public class EventUpdateRequest {

    @Size(min = 1, max = 200, message = "日程标题不能为空，且不能超过 200 字")
    private String title;

    @Pattern(regexp = "meeting|deep_block|other", message = "日程类型只能填 会议(meeting) / 深度块(deep_block) / 其他(other)")
    private String type;

    @Pattern(regexp = "^$|\\d{4}-\\d{2}-\\d{2}", message = "日期格式不正确，请按 YYYY-MM-DD 填写；留空表示时间待定")
    private String date;

    @Pattern(regexp = "^$|([01]\\d|2[0-3]):[0-5]\\d", message = "开始时间格式不正确，请按 HH:mm 填写，例如 09:30")
    private String start;

    @Pattern(regexp = "^$|([01]\\d|2[0-3]):[0-5]\\d", message = "结束时间格式不正确，请按 HH:mm 填写，例如 10:00")
    private String end;

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
}
