package com.icecode.workbench.attachment;

/**
 * 附件在前端的形态。
 *
 * <p>{@code image} 让前端决定它属于「解析图片」区还是「附件」区 ——
 * 不该让前端自己按后缀猜，判定规则只应存在于后端一处。</p>
 */
public class AttachmentVO {

    private final long id;
    private final String url;
    private final String name;
    private final String mimeType;
    private final long byteSize;
    private final Integer width;
    private final Integer height;
    private final boolean image;
    /** true 表示磁盘上已有同内容文件被复用，本次没写新文件 */
    private final boolean deduped;
    private final String createdAt;

    public AttachmentVO(long id, String url, String name, String mimeType, long byteSize,
                        Integer width, Integer height, boolean image, boolean deduped, String createdAt) {
        this.id = id;
        this.url = url;
        this.name = name;
        this.mimeType = mimeType;
        this.byteSize = byteSize;
        this.width = width;
        this.height = height;
        this.image = image;
        this.deduped = deduped;
        this.createdAt = createdAt;
    }

    public long getId() { return id; }
    public String getUrl() { return url; }
    public String getName() { return name; }
    public String getMimeType() { return mimeType; }
    public long getByteSize() { return byteSize; }
    public Integer getWidth() { return width; }
    public Integer getHeight() { return height; }
    public boolean isImage() { return image; }
    public boolean isDeduped() { return deduped; }
    public String getCreatedAt() { return createdAt; }
}
