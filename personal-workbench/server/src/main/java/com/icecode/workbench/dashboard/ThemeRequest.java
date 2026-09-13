package com.icecode.workbench.dashboard;

import javax.validation.constraints.NotBlank;
import javax.validation.constraints.Size;

public class ThemeRequest {

    @NotBlank(message = "今日主题不能为空")
    @Size(max = 100, message = "今日主题不能超过 100 字")
    private String theme;

    public String getTheme() { return theme; }
    public void setTheme(String theme) { this.theme = theme; }
}
