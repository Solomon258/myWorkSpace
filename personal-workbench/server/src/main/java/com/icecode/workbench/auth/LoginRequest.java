package com.icecode.workbench.auth;

import javax.validation.constraints.NotBlank;
import javax.validation.constraints.Size;

public class LoginRequest {

    @NotBlank(message = "用户名不能为空")
    @Size(max = 32, message = "用户名不能超过 32 字")
    private String username;

    @NotBlank(message = "密码不能为空")
    @Size(max = 72, message = "密码不能超过 72 字")
    private String password;

    public String getUsername() { return username; }
    public void setUsername(String username) { this.username = username; }
    public String getPassword() { return password; }
    public void setPassword(String password) { this.password = password; }
}
