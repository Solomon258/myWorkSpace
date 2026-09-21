package com.icecode.workbench.common;

public enum ErrorCode {

    RESOURCE_NOT_FOUND(1001, "请求的内容不存在"),
    INVALID_PARAMETER(1002, "请求参数不正确"),
    INTERNAL_ERROR(1003, "系统暂时不可用，请稍后重试"),
    NOT_INITIALIZED(2001, "工作台尚未完成首次配置"),
    NOT_LOGGED_IN(2002, "请先登录"),
    INVALID_PASSWORD(2003, "用户名或密码错误"),
    ALREADY_INITIALIZED(2004, "工作台已经完成首次配置"),
    INVALID_TIMEZONE(2005, "时区设置无效"),
    PASSWORD_TOO_LONG(2006, "密码按 UTF-8 编码后不能超过 72 字节"),
    INVALID_TASK_TRANSITION(3001, "当前任务状态不允许这样流转"),
    TASK_NOT_ACTIONABLE(3002, "已完成或已取消的任务不能执行此操作"),
    INVALID_EVENT_TIME(3003, "结束时间必须晚于开始时间；版本 A 不支持跨日日程"),
    BACKUP_FAILED(3004, "备份失败，请稍后重试"),
    RESTORE_INVALID(3005, "备份文件无效或与本应用版本不兼容"),
    CONFIRM_REQUIRED(3006, "该操作有风险，需要显式确认"),
    VAULT_NOT_CONFIGURED(3007, "尚未配置 Obsidian Vault，请到「设置 → Obsidian」填写库路径"),
    ARTICLE_PARSE_FAILED(3008, "没能从这个链接里读出文章信息，请确认它能在浏览器里正常打开"),
    UNSUPPORTED_LINK(3009, "暂不支持这个来源的链接，目前支持得到的分享链接"),
    // 附件相关：类型 / 大小 / 数量 / 占用 四个约束在不同入口都复用同一套码，
    // 具体原因（哪个文件、超了多少）由 BizException 的第二参数透传。
    ATTACHMENT_INVALID(3010, "附件不合法，请检查文件类型与大小"),
    ATTACHMENT_NOT_FOUND(3011, "附件不存在或已被删除"),
    ATTACHMENT_OCCUPIED(3012, "该附件已经挂在其他记录上"),
    ATTACHMENT_TOO_LARGE(3013, "上传的文件超过了大小上限"),
    ATTACHMENT_OVER_LIMIT(3014, "附件数量超过了上限"),
    // 视觉解析
    VISION_UNSUPPORTED(3015, "当前配置的模型不支持图片解析，请在设置里填写视觉模型"),
    VISION_FAILED(3016, "图片识别失败，可稍后重试"),
    /**
     * 得到分享页在「匿名 / 未登录」状态下只下发试读正文（2026-09-20 实测：1023 字 / 16 段，
     * 约为全文的 20%）。用户看到的「本篇内容剩余80%，继续学习」是前端硬编码文案，
     * 真正的边界在服务端 —— {@code packetInfo.has_authority} 与
     * {@code red_packet_data.red_packet_authority} 都为 false。
     *
     * <p>这不是解析失败：链接、标题、作者、课程名都读出来了，只是正文不完整。
     * 所以单独给一个码，让用户能分清「链接不对」和「这篇只给了我试读」——
     * 后者只要在设置里配上得到登录 Cookie 就能取到全文（自己的已购课程）。
     */
    ARTICLE_TRIAL_ONLY(3017, "这篇文章只拿到了试读部分，需要在设置里配置得到登录 Cookie 才能取全文");

    private final int code;
    private final String message;

    ErrorCode(int code, String message) {
        this.code = code;
        this.message = message;
    }

    public int getCode() {
        return code;
    }

    public String getMessage() {
        return message;
    }
}
