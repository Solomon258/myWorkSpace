package com.icecode.workbench.inbox;

import java.util.List;

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

    /**
     * 要挂到这条收录上的附件 id（先在 {@code POST /api/v1/attachments} 上传拿到 id）。
     * 留空或 null = 没有附件，行为与加这个字段之前完全一致。
     *
     * <p>注意附件**不会**随「整理确认」自动搬到生成的实体上 —— 那条搬运路径在
     * {@code InboxService.confirm} 里单独处理，因为确认时才知道最终落成哪一类。</p>
     */
    @Size(max = 9, message = "一条记录最多带 9 个附件")
    private List<Long> attachmentIds;

    public String getRaw() { return raw; }
    public void setRaw(String raw) { this.raw = raw; }
    public String getContentType() { return contentType; }
    public void setContentType(String contentType) { this.contentType = contentType; }
    public String getSource() { return source; }
    public void setSource(String source) { this.source = source; }
    public List<Long> getAttachmentIds() { return attachmentIds; }
    public void setAttachmentIds(List<Long> attachmentIds) { this.attachmentIds = attachmentIds; }
}
