package com.icecode.workbench.system;

import javax.validation.constraints.NotBlank;
import javax.validation.constraints.Pattern;

public class RestoreRequest {

    @NotBlank(message = "请选择要恢复的备份文件")
    @Pattern(regexp = "^[A-Za-z0-9._-]+\\.db$", message = "备份文件名不合法")
    private String fileName;

    private boolean confirm;

    public String getFileName() { return fileName; }
    public void setFileName(String fileName) { this.fileName = fileName; }
    public boolean isConfirm() { return confirm; }
    public void setConfirm(boolean confirm) { this.confirm = confirm; }
}
