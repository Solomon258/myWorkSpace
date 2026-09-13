package com.icecode.workbench.timeline;

public class TimelineItemVO {
    private final long id;
    private final String type;
    private final String category;
    private final String content;
    private final String createdAt;
    private final String day;

    public TimelineItemVO(long id, String type, String category, String content, String createdAt, String day) {
        this.id = id;
        this.type = type;
        this.category = category;
        this.content = content;
        this.createdAt = createdAt;
        this.day = day;
    }

    public long getId() { return id; }
    public String getType() { return type; }
    public String getCategory() { return category; }
    public String getContent() { return content; }
    public String getCreatedAt() { return createdAt; }
    public String getDay() { return day; }
}
