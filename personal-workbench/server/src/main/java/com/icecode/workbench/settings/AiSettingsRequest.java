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

    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }
    public String getBaseUrl() { return baseUrl; }
    public void setBaseUrl(String baseUrl) { this.baseUrl = baseUrl; }
    public String getModel() { return model; }
    public void setModel(String model) { this.model = model; }
    public String getApiKey() { return apiKey; }
    public void setApiKey(String apiKey) { this.apiKey = apiKey; }
}
