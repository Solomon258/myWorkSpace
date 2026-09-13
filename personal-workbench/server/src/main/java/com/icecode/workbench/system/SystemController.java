package com.icecode.workbench.system;

import java.util.List;
import java.util.Map;

import javax.validation.Valid;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.icecode.workbench.common.ApiResponse;
import com.icecode.workbench.common.BizException;
import com.icecode.workbench.common.ErrorCode;

@RestController
@RequestMapping("/api/v1/system")
public class SystemController {

    private final BackupService backupService;
    private final SystemService systemService;

    public SystemController(BackupService backupService, SystemService systemService) {
        this.backupService = backupService;
        this.systemService = systemService;
    }

    @PostMapping("/backup")
    public ApiResponse<BackupResultVO> backup(@RequestBody(required = false) BackupRequest request) {
        String targetPath = request == null ? null : request.getTargetPath();
        return ApiResponse.success(backupService.backupNow(targetPath));
    }

    @GetMapping("/backups")
    public ApiResponse<List<BackupFileVO>> backups() {
        return ApiResponse.success(backupService.listBackups());
    }

    @PostMapping("/restore")
    public ApiResponse<RestoreResultVO> restore(@Valid @RequestBody RestoreRequest request) {
        if (!request.isConfirm()) throw new BizException(ErrorCode.CONFIRM_REQUIRED);
        return ApiResponse.success(backupService.restore(request.getFileName()));
    }

    @PostMapping("/demo/clear")
    public ApiResponse<Map<String, Integer>> clearDemo(@RequestBody(required = false) Map<String, Object> body) {
        boolean confirm = body != null && Boolean.TRUE.equals(body.get("confirm"));
        return ApiResponse.success(systemService.clearDemo(confirm));
    }

    @PostMapping("/shutdown")
    public ApiResponse<String> shutdown() {
        systemService.shutdown();
        return ApiResponse.success("工作台正在关闭");
    }
}
