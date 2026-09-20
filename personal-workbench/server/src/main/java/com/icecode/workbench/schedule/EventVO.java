package com.icecode.workbench.schedule;

import java.util.List;

import com.icecode.workbench.attachment.AttachmentVO;

public class EventVO {
    private final long id;
    private final String title;
    private final String type;
    private final String date;
    private final String start;
    private final String end;
    private final Long sourceInboxId;
    private final String createdAt;
    private final boolean demo;
    /**
     * 同一条重复日程的组标识（= 组内首条的 id）；不重复的日程为 null。
     * 「每周三开周会」这类周期安排是**物化**成 N 条独立记录的（见 V8 迁移），
     * 前端靠这两个字段显示「每周 · 共 4 次」的标注，并让用户知道改其中一条只影响那一条。
     */
    private final Long repeatGroup;
    /** 该组共几期（不重复 = 1）。 */
    private final int repeatTotal;
    /**
     * 这条日程的附件（deleted=0）。列表接口一并带上，理由见 MemoVO 上的同一段注释。
     *
     * <p><b>重复日程只有首期有附件</b>（见 EventService.create 的说明），
     * 所以后面几期的 {@code attachments} 是空数组 —— 周视图因此在首期上显示徽标、
     * 其余期不显示，这是正确表现，不是漏了。</p>
     */
    private final List<AttachmentVO> attachments;

    public EventVO(long id, String title, String type, String date, String start, String end,
                   Long sourceInboxId, String createdAt, boolean demo, Long repeatGroup, int repeatTotal,
                   List<AttachmentVO> attachments) {
        this.id = id;
        this.title = title;
        this.type = type;
        this.date = date;
        this.start = start;
        this.end = end;
        this.sourceInboxId = sourceInboxId;
        this.createdAt = createdAt;
        this.demo = demo;
        this.repeatGroup = repeatGroup;
        this.repeatTotal = repeatTotal;
        this.attachments = attachments;
    }

    public long getId() { return id; }
    public String getTitle() { return title; }
    public String getType() { return type; }
    public String getDate() { return date; }
    public String getStart() { return start; }
    public String getEnd() { return end; }
    public Long getSourceInboxId() { return sourceInboxId; }
    public String getCreatedAt() { return createdAt; }
    public boolean isDemo() { return demo; }
    public Long getRepeatGroup() { return repeatGroup; }
    public int getRepeatTotal() { return repeatTotal; }
    public List<AttachmentVO> getAttachments() { return attachments; }
}
