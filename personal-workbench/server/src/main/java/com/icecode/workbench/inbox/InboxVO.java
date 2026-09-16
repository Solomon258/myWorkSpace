package com.icecode.workbench.inbox;

public class InboxVO {
    private final long id;
    private final String raw;
    private final String contentType;
    private final String source;
    private final String status;
    private final String createdAt;
    private final String processedAt;
    private final ClassifySuggestionVO ai;
    /** 'text' / 'image'：图片条目在整理页会带上原图缩略图，并且**不参与一键批量确认**。 */
    private final String origin;
    /** 视觉解析的进度（只对 origin='image' 有意义）：null / pending / running / success / failed。 */
    private final String parseStatus;
    private final String parseError;
    /** raw 是否被 4000 字上限截断过。前端据此在原文下面加一句「原文较长，已截断」。 */
    private final boolean rawTruncated;
    /** 解析用的图片附件 id；origin='image' 时才有，供前端展示原图与「重新解析」。 */
    private final Long sourceAttachmentId;
    /**
     * 这条收录在整理页里已经生成过几条实体（0 = 还没确认过）。
     * 图片条目常一次抽出多条，用户需要知道「我已经确认过哪几条了」。
     */
    private final int entityCount;

    public InboxVO(long id, String raw, String contentType, String source, String status,
                   String createdAt, String processedAt, ClassifySuggestionVO ai,
                   String origin, String parseStatus, String parseError, boolean rawTruncated,
                   Long sourceAttachmentId, int entityCount) {
        this.id = id;
        this.raw = raw;
        this.contentType = contentType;
        this.source = source;
        this.status = status;
        this.createdAt = createdAt;
        this.processedAt = processedAt;
        this.ai = ai;
        this.origin = origin;
        this.parseStatus = parseStatus;
        this.parseError = parseError;
        this.rawTruncated = rawTruncated;
        this.sourceAttachmentId = sourceAttachmentId;
        this.entityCount = entityCount;
    }

    public long getId() { return id; }
    public String getRaw() { return raw; }
    public String getContentType() { return contentType; }
    public String getSource() { return source; }
    public String getStatus() { return status; }
    public String getCreatedAt() { return createdAt; }
    public String getProcessedAt() { return processedAt; }
    public ClassifySuggestionVO getAi() { return ai; }
    public String getOrigin() { return origin; }
    public String getParseStatus() { return parseStatus; }
    public String getParseError() { return parseError; }
    public boolean isRawTruncated() { return rawTruncated; }
    public Long getSourceAttachmentId() { return sourceAttachmentId; }
    public int getEntityCount() { return entityCount; }
}
