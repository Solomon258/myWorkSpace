package com.icecode.workbench.transfer;

import java.util.List;

/**
 * 「移至」结果。
 *
 * <p>{@code id} 是**目标菜单里新记录**的 id，不是源记录 —— 源记录已经进回收站，
 * 前端提示要指向新记录，否则用户点开日志会找到一条已经不显示的记录。</p>
 *
 * <p>{@code warnings} 是「搬运过程中必然有损」的那些字段，逐条列出，前端必须展示：
 * 三张表的列不是一一对应的，静默丢弃会让用户以为东西还在。</p>
 */
public class TransferResultVO {

    private final String fromType;
    private final String toType;
    private final String fromLabel;
    private final String toLabel;
    private final long id;
    private final String title;
    private final List<String> warnings;
    /**
     * 跟着搬过去的附件条数（0 = 源记录本来就没有附件）。
     *
     * <p>单独报出来而不是混进 {@code warnings}：附件**跟着走了**是件好事、不是损失，
     * 而 warnings 在界面上会被拼成「注意：…」。但也不能不报 ——
     * 用户移完之后附件在哪里、还在不在，只有这句话能告诉他。</p>
     */
    private final int movedAttachments;

    public TransferResultVO(String fromType, String toType, String fromLabel, String toLabel,
                            long id, String title, List<String> warnings, int movedAttachments) {
        this.fromType = fromType;
        this.toType = toType;
        this.fromLabel = fromLabel;
        this.toLabel = toLabel;
        this.id = id;
        this.title = title;
        this.warnings = warnings;
        this.movedAttachments = movedAttachments;
    }

    public String getFromType() { return fromType; }
    public String getToType() { return toType; }
    public String getFromLabel() { return fromLabel; }
    public String getToLabel() { return toLabel; }
    public long getId() { return id; }
    public String getTitle() { return title; }
    public List<String> getWarnings() { return warnings; }
    public int getMovedAttachments() { return movedAttachments; }
}
