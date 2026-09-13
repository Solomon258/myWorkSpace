package com.icecode.workbench.schedule;

class EventRecord {
    long id;
    String title;
    String type;
    String date;
    String start;
    String end;
    Long sourceInboxId;
    String createdAt;
    boolean demo;
    /** 同一条重复日程的组标识（= 组内首条的 id）；不重复的日程为 null。见 V8 迁移。 */
    Long repeatGroup;
    /** 该组共几期（不重复 = 1）。 */
    int repeatTotal;
}
