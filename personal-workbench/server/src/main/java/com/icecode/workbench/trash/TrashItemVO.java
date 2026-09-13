package com.icecode.workbench.trash;

/**
 * 回收站里的一条已删记录。
 *
 * <p>回收站要在一屏里混排 5 种实体，所以字段是各表的最小公约数：
 * {@code type} 决定前端去调哪个恢复接口，{@code detail} 是辅助辨认的摘要
 * （光看标题很难认出是哪条，比如备忘常常标题为空、只有正文）。</p>
 */
public class TrashItemVO {

    private final String type;
    private final long id;
    private final String title;
    private final String detail;
    private final String deletedAt;
    private final int daysLeft;

    public TrashItemVO(String type, long id, String title, String detail, String deletedAt, int daysLeft) {
        this.type = type;
        this.id = id;
        this.title = title;
        this.detail = detail;
        this.deletedAt = deletedAt;
        this.daysLeft = daysLeft;
    }

    public String getType() { return type; }
    public long getId() { return id; }
    public String getTitle() { return title; }
    public String getDetail() { return detail; }
    public String getDeletedAt() { return deletedAt; }
    /** 距离不可恢复还剩几天（保留期 30 天，仅作提示，不做精确到小时的判断）。 */
    public int getDaysLeft() { return daysLeft; }
}
