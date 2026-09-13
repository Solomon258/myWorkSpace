package com.icecode.workbench.knowledge;

import java.util.List;

public class KnowledgeNoteVO {
    private final long id;
    private final String title;
    private final String content;
    private final List<String> tags;
    private final String vaultPath;
    private final String fileName;
    private final String syncStatus;
    private final String obsidianUrl;
    private final String createdAt;

    public KnowledgeNoteVO(long id, String title, String content, List<String> tags, String vaultPath,
                           String fileName, String syncStatus, String obsidianUrl, String createdAt) {
        this.id = id;
        this.title = title;
        this.content = content;
        this.tags = tags;
        this.vaultPath = vaultPath;
        this.fileName = fileName;
        this.syncStatus = syncStatus;
        this.obsidianUrl = obsidianUrl;
        this.createdAt = createdAt;
    }

    public long getId() { return id; }
    public String getTitle() { return title; }
    public String getContent() { return content; }
    public List<String> getTags() { return tags; }
    public String getVaultPath() { return vaultPath; }
    public String getFileName() { return fileName; }
    public String getSyncStatus() { return syncStatus; }
    public String getObsidianUrl() { return obsidianUrl; }
    public String getCreatedAt() { return createdAt; }
}
