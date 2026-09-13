package com.icecode.workbench.inbox;

public class AiJobVO {
    private final long id;
    private final String status;
    private final String refId;
    private final Long durationMs;
    private final String errorMsg;
    private final String createdAt;
    private final String updatedAt;

    public AiJobVO(long id, String status, String refId, Long durationMs, String errorMsg,
                   String createdAt, String updatedAt) {
        this.id = id;
        this.status = status;
        this.refId = refId;
        this.durationMs = durationMs;
        this.errorMsg = errorMsg;
        this.createdAt = createdAt;
        this.updatedAt = updatedAt;
    }

    public long getId() { return id; }
    public String getStatus() { return status; }
    public String getRefId() { return refId; }
    public Long getDurationMs() { return durationMs; }
    public String getErrorMsg() { return errorMsg; }
    public String getCreatedAt() { return createdAt; }
    public String getUpdatedAt() { return updatedAt; }
}
