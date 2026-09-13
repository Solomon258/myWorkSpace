package com.icecode.workbench.memo;

import java.util.List;

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

    public MemoVO(long id, String title, String content, String url, List<String> tags, String grp,
                  boolean pinned, String status, Long sourceInboxId, String createdAt, String updatedAt,
                  boolean demo) {
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
}
