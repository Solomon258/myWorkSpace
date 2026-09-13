package com.icecode.workbench.trash;

/** 恢复成功的结果。带上 title 是为了让前端 toast 能说清「恢复了什么」，而不是只说「恢复成功」。 */
public class TrashRestoreVO {

    private final String type;
    private final long id;
    private final String title;

    public TrashRestoreVO(String type, long id, String title) {
        this.type = type;
        this.id = id;
        this.title = title;
    }

    public String getType() { return type; }
    public long getId() { return id; }
    public String getTitle() { return title; }
}
