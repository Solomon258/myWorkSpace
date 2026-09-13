package com.icecode.workbench.settings;

import javax.validation.constraints.Size;

public class ObsidianSettingsRequest {

    @Size(max = 300, message = "Vault 路径不能超过 300 字")
    private String vaultPath;

    public String getVaultPath() { return vaultPath; }
    public void setVaultPath(String vaultPath) { this.vaultPath = vaultPath; }
}
