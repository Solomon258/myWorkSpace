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

    @GetMapping
    public ApiResponse<List<TaskVO>> list(
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String priority,
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) String dueFrom,
            @RequestParam(required = false) String dueTo,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "50") int size) {
        return ApiResponse.success(taskService.list(status, priority, keyword, dueFrom, dueTo, page, size));
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

    @DeleteMapping("/{id}")
    public ApiResponse<Void> delete(@PathVariable long id) {
        taskService.delete(id);
        return ApiResponse.success(null);
    }
}
