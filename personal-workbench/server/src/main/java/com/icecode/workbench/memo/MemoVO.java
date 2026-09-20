package com.icecode.workbench.memo;

import java.util.List;

import com.icecode.workbench.attachment.AttachmentVO;

public class MemoVO {
    private final long id;
    private final String title;
    private final String content;
    private final String url;
    private final List<String> tags;
    private final String grp;
    private final boolean pinned;
    private final String status;
    private final Long sourceInboxId;
    private final String createdAt;
    private final String updatedAt;
    private final boolean demo;
    /**
     * 这条备忘的附件（deleted=0，按 sort_order 排好）。
     *
     * <p>列表接口**直接带上**而不是让前端逐条再查一次：一屏几十张卡片各发一个请求就是 N+1，
     * 而 Hikari 池只有 4 个连接。列表里只显示前几张，但整份都返回 ——
     * 编辑弹层打开时正好复用同一份数据，不必再为它单独发一次请求。</p>
     */
    private final List<AttachmentVO> attachments;

    public MemoVO(long id, String title, String content, String url, List<String> tags, String grp,
                  boolean pinned, String status, Long sourceInboxId, String createdAt, String updatedAt,
                  boolean demo, List<AttachmentVO> attachments) {
        this.id = id;
        this.title = title;
        this.content = content;
        this.url = url;
        this.tags = tags;
        this.grp = grp;
        this.pinned = pinned;
        this.status = status;
        this.sourceInboxId = sourceInboxId;
        this.createdAt = createdAt;
        this.updatedAt = updatedAt;
        this.demo = demo;
        this.attachments = attachments;
    }

    public long getId() { return id; }
    public String getTitle() { return title; }
    public String getContent() { return content; }
    public String getUrl() { return url; }
    public List<String> getTags() { return tags; }
    public String getGrp() { return grp; }
    public boolean isPinned() { return pinned; }
    public String getStatus() { return status; }
    /** 收录来源 id：备忘可能是从收录条目确认生成的，「移至」时要把这个来源一起带过去。 */
    public Long getSourceInboxId() { return sourceInboxId; }
    public String getCreatedAt() { return createdAt; }
    public String getUpdatedAt() { return updatedAt; }
    public boolean isDemo() { return demo; }
    public List<AttachmentVO> getAttachments() { return attachments; }
}
