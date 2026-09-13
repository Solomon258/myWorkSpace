package com.icecode.workbench.settings;

public class SettingsVO {
    private final String username;
    private final boolean aiEnabled;
    private final String aiBaseUrl;
    private final String aiModel;
    private final String aiApiKeyMasked;
    private final boolean aiApiKeySet;
    private final String obsidianVaultPath;

    public SettingsVO(String username, boolean aiEnabled, String aiBaseUrl, String aiModel,
                      String aiApiKeyMasked, boolean aiApiKeySet, String obsidianVaultPath) {
        this.username = username;
        this.aiEnabled = aiEnabled;
        this.aiBaseUrl = aiBaseUrl;
        this.aiModel = aiModel;
        this.aiApiKeyMasked = aiApiKeyMasked;
        this.aiApiKeySet = aiApiKeySet;
        this.obsidianVaultPath = obsidianVaultPath;
    }

    public String getUsername() { return username; }
    public boolean isAiEnabled() { return aiEnabled; }
    public String getAiBaseUrl() { return aiBaseUrl; }
    public String getAiModel() { return aiModel; }
    public String getAiApiKeyMasked() { return aiApiKeyMasked; }
    public boolean isAiApiKeySet() { return aiApiKeySet; }
    public String getObsidianVaultPath() { return obsidianVaultPath; }
}
