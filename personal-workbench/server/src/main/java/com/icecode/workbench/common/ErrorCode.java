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
    VAULT_NOT_CONFIGURED(3007, "尚未配置 Obsidian Vault，请到「设置 → Obsidian」填写库路径");

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
