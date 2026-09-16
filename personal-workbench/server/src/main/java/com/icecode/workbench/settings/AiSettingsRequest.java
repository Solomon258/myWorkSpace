package com.icecode.workbench.settings;

import javax.validation.constraints.Size;

public class AiSettingsRequest {

    private boolean enabled;

    @Size(max = 200, message = "AI 地址不能超过 200 字")
    private String baseUrl;

    @Size(max = 100, message = "模型名不能超过 100 字")
    private String model;

    @Size(max = 200, message = "API Key 不能超过 200 字")
    private String apiKey;

    /**
     * 视觉模型名（图片解析用）。留空 = 关闭图片解析的模型调用，
     * 此时点「＋AI」会明确提示去设置页填写，而不是静默失败。
     */
    @Size(max = 100, message = "视觉模型名不能超过 100 字")
    private String visionModel;

    /** 「仅本地」：开启后图片不发往外部模型。默认关闭（用户明示开启才生效）。 */
    private boolean visionLocalOnly;

    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }
    public String getBaseUrl() { return baseUrl; }
    public void setBaseUrl(String baseUrl) { this.baseUrl = baseUrl; }
    public String getModel() { return model; }
    public void setModel(String model) { this.model = model; }
    public String getApiKey() { return apiKey; }
    public void setApiKey(String apiKey) { this.apiKey = apiKey; }
    public String getVisionModel() { return visionModel; }
    public void setVisionModel(String visionModel) { this.visionModel = visionModel; }
    public boolean isVisionLocalOnly() { return visionLocalOnly; }
    public void setVisionLocalOnly(boolean visionLocalOnly) { this.visionLocalOnly = visionLocalOnly; }
}
