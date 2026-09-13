package com.icecode.workbench.auth;

import javax.validation.constraints.NotBlank;
import javax.validation.constraints.Size;

public class InitRequest {

    @NotBlank(message = "用户名不能为空")
    @Size(min = 2, max = 32, message = "用户名长度需要在 2 到 32 字之间")
    private String username;

    @NotBlank(message = "密码不能为空")
    @Size(min = 8, max = 72, message = "密码长度需要在 8 到 72 字之间")
    private String password;

    @NotBlank(message = "时区不能为空")
    private String timezone = "Asia/Shanghai";

    private boolean seedDemo = true;

    public String getUsername() { return username; }
    public void setUsername(String username) { this.username = username; }
    public String getPassword() { return password; }
    public void setPassword(String password) { this.password = password; }
    public String getTimezone() { return timezone; }
    public void setTimezone(String timezone) { this.timezone = timezone; }
    public boolean isSeedDemo() { return seedDemo; }
    public void setSeedDemo(boolean seedDemo) { this.seedDemo = seedDemo; }
}
