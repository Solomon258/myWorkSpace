package com.icecode.workbench.system;

public class BackupFileVO {
    private final String name;
    private final long sizeBytes;
    private final String modifiedAt;

    public BackupFileVO(String name, long sizeBytes, String modifiedAt) {
        this.name = name;
        this.sizeBytes = sizeBytes;
        this.modifiedAt = modifiedAt;
    }

    public String getName() { return name; }
    public long getSizeBytes() { return sizeBytes; }
    public String getModifiedAt() { return modifiedAt; }
}
