package com.icecode.workbench.collect;

/** 收藏结果。前端据此告诉用户「存到哪了」，所以目录和状态都要回传。 */
public class CollectResultVO {

    public final long noteId;
    public final String title;
    public final String author;
    public final String collection;
    public final String platform;
    /** Vault 内相对路径；写入失败为 null。 */
    public final String vaultPath;
    /** synced = 已写入 Vault；failed = 只落了库，可重试。 */
    public final String syncStatus;

    public CollectResultVO(long noteId, ArticleMeta meta, String vaultPath, String syncStatus) {
        this.noteId = noteId;
        this.title = meta.title;
        this.author = meta.author;
        this.collection = meta.collection;
        this.platform = meta.platformLabel();
        this.vaultPath = vaultPath;
        this.syncStatus = syncStatus;
    }
}
