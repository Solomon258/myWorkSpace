package com.icecode.workbench.settings;

public class SettingsVO {
    private final String username;
    private final boolean aiEnabled;
    private final String aiBaseUrl;
    private final String aiModel;
    private final String aiApiKeyMasked;
    private final boolean aiApiKeySet;
    private final String obsidianVaultPath;
    /** 视觉模型名（图片解析用）。空 = 没配，此时「＋AI 解析图片」会明确报出该去哪儿填。 */
    private final String aiVisionModel;
    /**
     * 「仅本地」开关：开启后图片不发往外部模型。
     * 前端据此在「＋AI」按钮上给出提示 —— 用户点下去才知道为什么没解析。
     */
    private final boolean aiVisionLocalOnly;
    /**
     * 是否已配置「得到登录 Cookie」。
     *
     * <p>只回传布尔值，不回传明文 —— Cookie 等同于账号凭据，把它送回浏览器等于多一份
     * 可被截图 / 缓存 / 插件读取的副本，而界面上只需要回答「配没配」这个问题。
     */
    private final boolean dedaoCookieSet;

    public SettingsVO(String username, boolean aiEnabled, String aiBaseUrl, String aiModel,
                      String aiApiKeyMasked, boolean aiApiKeySet, String obsidianVaultPath,
                      String aiVisionModel, boolean aiVisionLocalOnly, boolean dedaoCookieSet) {
        this.username = username;
        this.aiEnabled = aiEnabled;
        this.aiBaseUrl = aiBaseUrl;
        this.aiModel = aiModel;
        this.aiApiKeyMasked = aiApiKeyMasked;
        this.aiApiKeySet = aiApiKeySet;
        this.obsidianVaultPath = obsidianVaultPath;
        this.aiVisionModel = aiVisionModel;
        this.aiVisionLocalOnly = aiVisionLocalOnly;
        this.dedaoCookieSet = dedaoCookieSet;
    }

    public String getUsername() { return username; }
    public boolean isAiEnabled() { return aiEnabled; }
    public String getAiBaseUrl() { return aiBaseUrl; }
    public String getAiModel() { return aiModel; }
    public String getAiApiKeyMasked() { return aiApiKeyMasked; }
    public boolean isAiApiKeySet() { return aiApiKeySet; }
    public String getObsidianVaultPath() { return obsidianVaultPath; }
    public String getAiVisionModel() { return aiVisionModel; }
    public boolean isAiVisionLocalOnly() { return aiVisionLocalOnly; }
    public boolean isDedaoCookieSet() { return dedaoCookieSet; }
}
