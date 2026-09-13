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

    public InboxVO(long id, String raw, String contentType, String source, String status,
                   String createdAt, String processedAt, ClassifySuggestionVO ai) {
        this.id = id;
        this.raw = raw;
        this.contentType = contentType;
        this.source = source;
        this.status = status;
        this.createdAt = createdAt;
        this.processedAt = processedAt;
        this.ai = ai;
    }

    public long getId() { return id; }
    public String getRaw() { return raw; }
    public String getContentType() { return contentType; }
    public String getSource() { return source; }
    public String getStatus() { return status; }
    public String getCreatedAt() { return createdAt; }
    public String getProcessedAt() { return processedAt; }
    public ClassifySuggestionVO getAi() { return ai; }
}
