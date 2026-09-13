package com.icecode.workbench.task;

import javax.validation.constraints.NotBlank;
import javax.validation.constraints.Pattern;

public class TaskStatusRequest {

    @NotBlank(message = "任务状态不能为空")
    @Pattern(regexp = "todo|doing|done|canceled", message = "任务状态只能填 待办(todo) / 进行中(doing) / 已完成(done) / 已取消(canceled)")
    private String status;

    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
}
