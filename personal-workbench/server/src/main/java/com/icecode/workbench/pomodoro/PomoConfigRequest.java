package com.icecode.workbench.pomodoro;

import javax.validation.constraints.Max;
import javax.validation.constraints.Min;
import javax.validation.constraints.NotNull;

public class PomoConfigRequest {

    @NotNull(message = "专注时长不能为空")
    @Min(value = 1, message = "专注时长不能小于 1 分钟")
    @Max(value = 120, message = "专注时长不能超过 120 分钟")
    private Integer work;

    @NotNull(message = "短休息时长不能为空")
    @Min(value = 1, message = "短休息时长不能小于 1 分钟")
    @Max(value = 60, message = "短休息时长不能超过 60 分钟")
    private Integer shortBreak;

    @NotNull(message = "长休息时长不能为空")
    @Min(value = 1, message = "长休息时长不能小于 1 分钟")
    @Max(value = 60, message = "长休息时长不能超过 60 分钟")
    private Integer longBreak;

    private boolean auto;

    public Integer getWork() { return work; }
    public void setWork(Integer work) { this.work = work; }
    public Integer getShortBreak() { return shortBreak; }
    public void setShortBreak(Integer shortBreak) { this.shortBreak = shortBreak; }
    public Integer getLongBreak() { return longBreak; }
    public void setLongBreak(Integer longBreak) { this.longBreak = longBreak; }
    public boolean isAuto() { return auto; }
    public void setAuto(boolean auto) { this.auto = auto; }
}
