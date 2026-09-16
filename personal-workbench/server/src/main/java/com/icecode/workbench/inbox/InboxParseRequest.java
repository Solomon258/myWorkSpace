package com.icecode.workbench.inbox;

import java.util.List;

import javax.validation.constraints.NotEmpty;
import javax.validation.constraints.Pattern;
import javax.validation.constraints.Size;

/**
 * 触发图片解析。
 *
 * <p>传的是**附件 id** 而不是收录条目 id：解析发生在「图片已上传、条目还没建」这一刻 ——
 * 如果要求先有收录条目，前端就得先建一条空条目再回填，而空条目在解析失败时会永远
 * 留在收集箱里变成垃圾。现在的顺序是「传图 → 触发解析 → 解析成功才建条目」，
 * 失败时条目降级成待整理，用户能看见也能删。</p>
 */
public class InboxParseRequest {

    @NotEmpty(message = "请先选择要解析的图片")
    @Size(max = 3, message = "一次最多解析 3 张图片")
    private List<Long> attachmentIds;

    /**
     * 来源，与收录条目上的同一个字段。图片多数来自微信转发，前端会带上 wecom。
     */
    @Pattern(regexp = "web|wecom", message = "来源只能填 网页(web) / 企业微信(wecom)")
    private String source = "web";

    public List<Long> getAttachmentIds() { return attachmentIds; }
    public void setAttachmentIds(List<Long> attachmentIds) { this.attachmentIds = attachmentIds; }
    public String getSource() { return source; }
    public void setSource(String source) { this.source = source; }
}
