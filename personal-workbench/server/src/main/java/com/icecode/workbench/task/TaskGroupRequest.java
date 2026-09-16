package com.icecode.workbench.task;

import javax.validation.constraints.NotBlank;
import javax.validation.constraints.Pattern;

/** 只改任务的工作 / 生活分组（卡片上的分组徽标走这里）。 */
public class TaskGroupRequest {

    @NotBlank(message = "分组不能为空")
    @Pattern(regexp = "work|life", message = "分组只能填 work（工作）或 life（生活）")
    private String grp;

    public String getGrp() { return grp; }
    public void setGrp(String grp) { this.grp = grp; }
}
