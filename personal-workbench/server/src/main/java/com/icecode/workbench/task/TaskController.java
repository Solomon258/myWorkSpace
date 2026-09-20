package com.icecode.workbench.task;

import java.util.List;

import javax.validation.Valid;

import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.icecode.workbench.common.ApiResponse;

@RestController
@RequestMapping("/api/v1/tasks")
public class TaskController {

    private final TaskService taskService;

    public TaskController(TaskService taskService) {
        this.taskService = taskService;
    }

    /**
     * 任务列表。{@code sort} 是 2026-09-20 新增的排序口径，只有两个合法取值：
     * {@code due}（按截止时间，默认）与 {@code updated}（按最后修改时间）。
     *
     * <p>排序放在服务端而不是前端本地重排，理由与 {@code keyword} 必须在服务端过滤一样：
     * 任务页一次只加载 100 条，本地排序只能在这 100 条里做 ——
     * 选「按最后修改时间」的用户想看的恰恰是**最近改过的那一批**，
     * 而它们完全可能不在「按截止时间排出来的前 100 条」里。
     * 本地排会把这件事静默做错（用户看到的是「最近改的任务一条都没有」）。</p>
     */
    @GetMapping
    public ApiResponse<List<TaskVO>> list(
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String priority,
            @RequestParam(required = false) String grp,
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) String dueFrom,
            @RequestParam(required = false) String dueTo,
            @RequestParam(required = false) String sort,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "50") int size) {
        return ApiResponse.success(taskService.list(status, priority, grp, keyword, dueFrom, dueTo, sort, page, size));
    }

    @PostMapping
    public ApiResponse<TaskVO> create(@Valid @RequestBody TaskCreateRequest request) {
        return ApiResponse.success(taskService.create(request));
    }

    @PatchMapping("/{id}")
    public ApiResponse<TaskVO> update(@PathVariable long id, @Valid @RequestBody TaskUpdateRequest request) {
        return ApiResponse.success(taskService.update(id, request));
    }

    @PostMapping("/{id}/status")
    public ApiResponse<TaskVO> changeStatus(@PathVariable long id, @Valid @RequestBody TaskStatusRequest request) {
        return ApiResponse.success(taskService.changeStatus(id, request.getStatus()));
    }

    @PostMapping("/{id}/postpone")
    public ApiResponse<TaskVO> postpone(@PathVariable long id) {
        return ApiResponse.success(taskService.postpone(id));
    }

    /**
     * 改任务的工作 / 生活分组。
     *
     * <p>单独开一个端点而不是复用 {@code PATCH /{id}}：卡片上的分组徽标是一键切换，
     * 走 PATCH 就得把其余字段全回传一遍，会把并发场景下别处的修改覆盖掉（丢失更新）。
     * 语义上是「把这条任务挪到另一个分组」，和「把卡片拖到另一条泳道」同类，
     * 所以用 POST 动作用路径而非 PATCH 改字段。</p>
     */
    @PostMapping("/{id}/group")
    public ApiResponse<TaskVO> changeGroup(@PathVariable long id, @Valid @RequestBody TaskGroupRequest request) {
        return ApiResponse.success(taskService.changeGroup(id, request.getGrp()));
    }

    @DeleteMapping("/{id}")
    public ApiResponse<Void> delete(@PathVariable long id) {
        taskService.delete(id);
        return ApiResponse.success(null);
    }
}
