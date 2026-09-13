package com.icecode.workbench.inbox;

public class ConfirmResultVO {
    private final long inboxId;
    private final String category;
    private final long entityId;
    private final boolean alreadyConfirmed;

    public ConfirmResultVO(long inboxId, String category, long entityId, boolean alreadyConfirmed) {
        this.inboxId = inboxId;
        this.category = category;
        this.entityId = entityId;
        this.alreadyConfirmed = alreadyConfirmed;
    }

    public long getInboxId() { return inboxId; }
    public String getCategory() { return category; }
    public long getEntityId() { return entityId; }
    public boolean isAlreadyConfirmed() { return alreadyConfirmed; }
}
