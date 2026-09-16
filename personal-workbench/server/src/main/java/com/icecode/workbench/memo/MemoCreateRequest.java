package com.icecode.workbench.memo;

import java.util.List;

import javax.validation.constraints.Pattern;
import javax.validation.constraints.Size;

public class MemoCreateRequest {

    /**
     * 正文与标题**至少写一项**即可（原来正文是 @NotBlank 必填）。
     *
     * <p>去掉必填是因为「速查信息」这类备忘本来就只有一行标题：「班车 7:20 小区门口」
     * 填在标题里就够了，强迫用户再往正文里抄一遍只会让人放弃记录。
     * 但两者不能同时为空 —— 那会在列表里留下一张点不出任何内容的空卡片。
     * 这条「至少一项」是跨字段约束，注解表达不了，落在 {@code MemoService.requireTitleOrContent}。</p>
     */
    @Size(max = 4000, message = "备忘内容不能超过 4000 字")
    private String content;

    @Size(max = 200, message = "备忘标题不能超过 200 字")
    private String title;

    @Size(max = 500, message = "标签不能超过 500 字")
    private String tags;

    @Pattern(regexp = "auto|work|life", message = "备忘空间只能填 自动(auto) / 工作(work) / 生活(life)")
    private String grp = "auto";

    /**
     * 要挂到这条备忘上的附件 id（先在 {@code POST /api/v1/attachments} 上传拿到 id）。
     * 留空或 null = 没有附件，行为与加这个字段之前完全一致。
     */
    @Size(max = 9, message = "一条记录最多带 9 个附件")
    private List<Long> attachmentIds;

    public String getContent() { return content; }
    public void setContent(String content) { this.content = content; }
    public String getTitle() { return title; }
    public void setTitle(String title) { this.title = title; }
    public String getTags() { return tags; }
    public void setTags(String tags) { this.tags = tags; }
    public String getGrp() { return grp; }
    public void setGrp(String grp) { this.grp = grp; }
    public List<Long> getAttachmentIds() { return attachmentIds; }
    public void setAttachmentIds(List<Long> attachmentIds) { this.attachmentIds = attachmentIds; }
}
