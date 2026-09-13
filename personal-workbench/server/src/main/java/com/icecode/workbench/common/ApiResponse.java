package com.icecode.workbench.common;

import com.icecode.workbench.util.TimeUtil;

public class ApiResponse<T> {

    private final int code;
    private final String message;
    private final T data;
    private final long timestamp;

    private ApiResponse(int code, String message, T data) {
        this.code = code;
        this.message = message;
        this.data = data;
        this.timestamp = TimeUtil.epochMillis();
    }

    public static <T> ApiResponse<T> success(T data) {
        return new ApiResponse<T>(0, "ok", data);
    }

    public static <T> ApiResponse<T> failure(ErrorCode errorCode) {
        return new ApiResponse<T>(errorCode.getCode(), errorCode.getMessage(), null);
    }

    /**
     * 带自定义说明的失败响应：业务抛 {@code new BizException(code, "具体原因")} 时，
     * 必须把这句具体原因透传给前端，否则用户只会看到枚举上的笼统文案。
     */
    public static <T> ApiResponse<T> failure(ErrorCode errorCode, String message) {
        return new ApiResponse<T>(errorCode.getCode(),
                message == null || message.trim().isEmpty() ? errorCode.getMessage() : message, null);
    }

    public int getCode() {
        return code;
    }

    public String getMessage() {
        return message;
    }

    public T getData() {
        return data;
    }

    public long getTimestamp() {
        return timestamp;
    }
}
