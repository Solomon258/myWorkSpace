package com.icecode.workbench.attachment;

/** attachment 表的一行。字段用包级可见，与 InboxRecord 保持一致。 */
class AttachmentRecord {

    long id;
    /** task / schedule_event / memo / knowledge_note / inbox_item；为 null 表示「已上传、还没绑定实体」 */
    String ownerType;
    Long ownerId;
    /** 落盘文件名（sha256 前 16 位 + 后缀），永不来自用户原文件名 */
    String fileName;
    /** 用户原始文件名，只用于展示与报错 */
    String originalName;
    String mimeType;
    long byteSize;
    Integer width;
    Integer height;
    String sha256;
    int sortOrder;
    String createdAt;
}
