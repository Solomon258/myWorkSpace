package com.icecode.workbench.pomodoro;

import javax.validation.constraints.Max;
import javax.validation.constraints.Min;
import javax.validation.constraints.NotNull;

public class PomodoroCreateRequest {

    private Long taskId;

    @NotNull(message = "番茄钟时长不能为空")
    @Min(value = 1, message = "番茄钟时长不能小于 1 分钟")
    @Max(value = 120, message = "番茄钟时长不能超过 120 分钟")
    private Integer minutes;

    public Long getTaskId() { return taskId; }
    public void setTaskId(Long taskId) { this.taskId = taskId; }
    public Integer getMinutes() { return minutes; }
    public void setMinutes(Integer minutes) { this.minutes = minutes; }
}
