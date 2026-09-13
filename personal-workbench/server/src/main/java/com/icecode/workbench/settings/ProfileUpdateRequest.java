package com.icecode.workbench.settings;

import javax.validation.constraints.Size;

public class ProfileUpdateRequest {

    @Size(min = 2, max = 32, message = "用户名长度需要在 2-32 之间")
    private String username;

    private String currentPassword;

    @Size(min = 8, max = 72, message = "新密码长度需要在 8-72 之间")
    private String newPassword;

    public String getUsername() { return username; }
    public void setUsername(String username) { this.username = username; }
    public String getCurrentPassword() { return currentPassword; }
    public void setCurrentPassword(String currentPassword) { this.currentPassword = currentPassword; }
    public String getNewPassword() { return newPassword; }
    public void setNewPassword(String newPassword) { this.newPassword = newPassword; }
}
