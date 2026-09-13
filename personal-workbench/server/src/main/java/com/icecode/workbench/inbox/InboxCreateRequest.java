package com.icecode.workbench.inbox;

import javax.validation.constraints.NotBlank;
import javax.validation.constraints.Pattern;
import javax.validation.constraints.Size;

public class InboxCreateRequest {

    @NotBlank(message = "收录内容不能为空")
    @Size(max = 4000, message = "收录内容不能超过 4000 字")
    private String raw;

    @Pattern(regexp = "text|voice", message = "内容类型只能填 文本(text) / 语音(voice)")
    private String contentType = "text";

    @Pattern(regexp = "web|wecom", message = "来源只能填 网页(web) / 企业微信(wecom)")
    private String source = "web";

    public String getRaw() { return raw; }
    public void setRaw(String raw) { this.raw = raw; }
    public String getContentType() { return contentType; }
    public void setContentType(String contentType) { this.contentType = contentType; }
    public String getSource() { return source; }
    public void setSource(String source) { this.source = source; }
}
