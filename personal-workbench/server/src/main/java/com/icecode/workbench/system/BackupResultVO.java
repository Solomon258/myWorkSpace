package com.icecode.workbench.system;

public class BackupResultVO {
    private final String file;
    private final long sizeBytes;

    public BackupResultVO(String file, long sizeBytes) {
        this.file = file;
        this.sizeBytes = sizeBytes;
    }

    public String getFile() { return file; }
    public long getSizeBytes() { return sizeBytes; }
}
