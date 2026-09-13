package com.icecode.workbench.transfer;

import javax.validation.Valid;

import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.icecode.workbench.common.ApiResponse;

/**
 * 「移至」：把记录在任务 / 日程 / 备忘三个菜单之间搬运。
 *
 * <p>刻意不做成「PATCH /api/v1/tasks/{id}」那种形式：移动是**跨实体**的动作，
 * 挂在任何一个实体的路径下都会让另外两个方向的调用看起来像走错了门。
 * 一个独立的 {@code /api/v1/transfers} 也让前端只需要一个方法。</p>
 */
@RestController
@RequestMapping("/api/v1/transfers")
public class TransferController {

    private final TransferService transferService;

    public TransferController(TransferService transferService) {
        this.transferService = transferService;
    }

    @PostMapping
    public ApiResponse<TransferResultVO> transfer(@Valid @RequestBody TransferRequest request) {
        return ApiResponse.success(transferService.transfer(request));
    }
}
