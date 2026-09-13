package com.icecode.workbench.system;

import java.util.Map;

public class RestoreResultVO {
    private final String restoredFrom;
    private final String preRestoreBackup;
    private final int schemaMigrations;
    private final Map<String, Integer> tableCounts;

    public RestoreResultVO(String restoredFrom, String preRestoreBackup, int schemaMigrations,
                           Map<String, Integer> tableCounts) {
        this.restoredFrom = restoredFrom;
        this.preRestoreBackup = preRestoreBackup;
        this.schemaMigrations = schemaMigrations;
        this.tableCounts = tableCounts;
    }

    public String getRestoredFrom() { return restoredFrom; }
    public String getPreRestoreBackup() { return preRestoreBackup; }
    public int getSchemaMigrations() { return schemaMigrations; }
    public Map<String, Integer> getTableCounts() { return tableCounts; }
}
