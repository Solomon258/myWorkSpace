package com.icecode.workbench.common;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.validation.BindingResult;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.multipart.MultipartException;

@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger LOGGER = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(BizException.class)
    public ResponseEntity<ApiResponse<Void>> handleBizException(BizException exception) {
        ErrorCode errorCode = exception.getErrorCode();
        HttpStatus status;
        if (errorCode == ErrorCode.NOT_INITIALIZED || errorCode == ErrorCode.ALREADY_INITIALIZED) {
            status = HttpStatus.CONFLICT;
        } else if (errorCode == ErrorCode.NOT_LOGGED_IN) {
            status = HttpStatus.UNAUTHORIZED;
        } else if (errorCode == ErrorCode.INVALID_PASSWORD) {
            status = HttpStatus.FORBIDDEN;
        } else if (errorCode == ErrorCode.RESOURCE_NOT_FOUND
                || errorCode == ErrorCode.ATTACHMENT_NOT_FOUND) {
            // 附件也是资源：取一个不存在的附件应当是 404，与其它资源保持同一语义
            status = HttpStatus.NOT_FOUND;
        } else {
            status = HttpStatus.BAD_REQUEST;
        }
        // 透传 BizException 上的具体说明（new BizException(code, "原因")），
        // 否则前端只能拿到 ErrorCode 的笼统文案，用户不知道到底哪里错了。
        return ResponseEntity.status(status)
                .body(ApiResponse.<Void>failure(errorCode, exception.getMessage()));
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ApiResponse<Void>> handleValidationException(MethodArgumentNotValidException exception) {
        // 字段校验注解上写明了具体原因（如「开始时间格式不正确，请按 HH:mm 填写，例如 09:30」），
        // 必须透传，否则前端只会显示「请求参数不正确」，用户不知道改哪里。
        // 约定：所有请求 DTO 的约束都必须自带 message，由 ValidationMessageTest 扫描守着。
        String fieldMessage = null;
        BindingResult bindingResult = exception.getBindingResult();
        if (bindingResult != null && bindingResult.getFieldError() != null) {
            fieldMessage = bindingResult.getFieldError().getDefaultMessage();
        }
        if (fieldMessage == null || fieldMessage.trim().isEmpty()) {
            fieldMessage = "请求参数不正确，请检查后重试";
        }
        return ResponseEntity.badRequest()
                .body(ApiResponse.<Void>failure(ErrorCode.INVALID_PARAMETER, fieldMessage));
    }

    /**
     * 请求体不是合法 JSON（或压根没传）。
     * 不处理的话会落到下面的兜底分支，变成 500「系统暂时不可用」——
     * 用户写错请求体会被误判成服务端故障，自动化里会据此触发无意义的重试和告警。
     */
    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ApiResponse<Void>> handleUnreadableBody(HttpMessageNotReadableException exception) {
        String raw = exception.getMessage() == null ? "" : exception.getMessage();
        String reason = raw.contains("Required request body is missing")
                ? "请求体不能为空，请检查是否漏传了 JSON 内容"
                : "请求体不是合法的 JSON，请检查语法";
        return ResponseEntity.badRequest()
                .body(ApiResponse.<Void>failure(ErrorCode.INVALID_PARAMETER, reason));
    }

    /** 用错 HTTP 方法（例如拿 GET 去打只接受 POST 的接口）。 */
    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    public ResponseEntity<ApiResponse<Void>> handleMethodNotSupported(HttpRequestMethodNotSupportedException exception) {
        String method = exception.getMethod() == null ? "该" : exception.getMethod();
        String supported = exception.getSupportedHttpMethods() == null ? ""
                : "，允许的方法是 " + exception.getSupportedHttpMethods();
        return ResponseEntity.status(HttpStatus.METHOD_NOT_ALLOWED)
                .body(ApiResponse.<Void>failure(ErrorCode.INVALID_PARAMETER,
                        "该接口不支持 " + method + " 请求" + supported));
    }

    /** 路径/查询参数类型不对（例如 ?limit=abc）。 */
    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ResponseEntity<ApiResponse<Void>> handleTypeMismatch(MethodArgumentTypeMismatchException exception) {
        String name = exception.getName() == null ? "参数" : "参数 " + exception.getName();
        String expected = exception.getRequiredType() == null ? "" : "，应为 " + exception.getRequiredType().getSimpleName();
        return ResponseEntity.badRequest()
                .body(ApiResponse.<Void>failure(ErrorCode.INVALID_PARAMETER,
                        name + "格式不正确" + expected));
    }

    /**
     * 上传的附件超过了 {@code spring.servlet.multipart} 配置的上限。
     *
     * <p>这个异常在进入 Controller 之前就抛出了，业务层根本没机会拦。不处理的话会落到
     * 兜底分支变成 500 —— 用户传了个大文件，却被告知「系统暂时不可用」，
     * 完全不知道问题出在自己的文件上。</p>
     */
    @ExceptionHandler(MaxUploadSizeExceededException.class)
    public ResponseEntity<ApiResponse<Void>> handleUploadTooLarge(MaxUploadSizeExceededException exception) {
        LOGGER.warn("Rejected oversized upload: {}", exception.getMessage());
        return ResponseEntity.badRequest()
                .body(ApiResponse.<Void>failure(ErrorCode.ATTACHMENT_TOO_LARGE,
                        "文件太大：单个文档最大 100 MB，单张图片最大 10 MB"));
    }

    /** 请求不是合法的 multipart（例如没带 boundary），同样不能落 500。 */
    @ExceptionHandler(MultipartException.class)
    public ResponseEntity<ApiResponse<Void>> handleMultipart(MultipartException exception) {
        LOGGER.warn("Malformed multipart request: {}", exception.getMessage());
        return ResponseEntity.badRequest()
                .body(ApiResponse.<Void>failure(ErrorCode.ATTACHMENT_INVALID,
                        "上传请求格式不正确，请重新选择文件后再试"));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiResponse<Void>> handleException(Exception exception) {
        LOGGER.error("Unhandled application error", exception);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(ApiResponse.<Void>failure(ErrorCode.INTERNAL_ERROR));
    }
}
