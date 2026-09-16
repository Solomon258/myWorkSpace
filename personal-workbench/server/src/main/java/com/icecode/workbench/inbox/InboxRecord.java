package com.icecode.workbench.inbox;

class InboxRecord {
    long id;
    String raw;
    String contentType;
    String source;
    String status;
    String aiCategory;
    Double aiConfidence;
    String aiPayload;
    String processedAt;
    String createdAt;
    String updatedAt;
    /** 'text' = 手打/转发进来的文字；'image' = 来自图片解析（V10）。 */
    String origin;
    /** 只描述「视觉解析」这一步：null / 'pending' / 'running' / 'success' / 'failed'。 */
    String parseStatus;
    String parseError;
    /** raw_content 是否被 4000 字上限截断过 —— 用户有权知道原文不完整。 */
    boolean rawTruncated;
    /** 解析用的图片附件 id（origin='image' 时才有），用于失败后重试。 */
    Long sourceAttachmentId;
}
