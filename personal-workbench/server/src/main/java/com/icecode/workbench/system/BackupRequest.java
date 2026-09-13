package com.icecode.workbench.system;

import javax.validation.constraints.Size;

public class BackupRequest {

    @Size(max = 500, message = "备份路径不能超过 500 字")
    private String targetPath;

    public String getTargetPath() { return targetPath; }
    public void setTargetPath(String targetPath) { this.targetPath = targetPath; }
}
