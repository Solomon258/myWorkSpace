package com.icecode.workbench.knowledge;

public class IndexStatusVO {
    private final boolean configured;
    private final String vaultName;
    private final int fileCount;
    private final int chunkCount;
    private final boolean llmEnabled;

    public IndexStatusVO(boolean configured, String vaultName, int fileCount, int chunkCount, boolean llmEnabled) {
        this.configured = configured;
        this.vaultName = vaultName;
        this.fileCount = fileCount;
        this.chunkCount = chunkCount;
        this.llmEnabled = llmEnabled;
    }

    public boolean isConfigured() { return configured; }
    public String getVaultName() { return vaultName; }
    public int getFileCount() { return fileCount; }
    public int getChunkCount() { return chunkCount; }
    public boolean isLlmEnabled() { return llmEnabled; }
}
