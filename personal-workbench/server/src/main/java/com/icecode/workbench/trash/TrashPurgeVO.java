package com.icecode.workbench.trash;

/**
 * 彻底删除一条的结果。
 *
 * <p>字段与 {@link TrashRestoreVO} 同形，但仍然分成两个类：这两个动作的**承诺是相反的**
 * （恢复 = 还能找回来，彻底删除 = 再也找不回来）。合成一个 VO 之后，前端很容易把
 * 「已彻底删除」的 toast 接到恢复那条分支上，而那种错误在界面上只表现为一句文案不对，
 * 不报错、也查不出来。</p>
 */
public class TrashPurgeVO {

    private final String type;
    private final long id;
    private final String title;

    public TrashPurgeVO(String type, long id, String title) {
        this.type = type;
        this.id = id;
        this.title = title;
    }

    public String getType() { return type; }
    public long getId() { return id; }
    public String getTitle() { return title; }
}
