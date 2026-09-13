package com.icecode.workbench.inbox;

import java.util.List;
import java.util.Map;

import javax.validation.Valid;

import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.icecode.workbench.common.ApiResponse;

@RestController
@RequestMapping("/api/v1/inbox")
public class InboxController {

    private final InboxService inboxService;
    private final AiJobService aiJobService;

    public InboxController(InboxService inboxService, AiJobService aiJobService) {
        this.inboxService = inboxService;
        this.aiJobService = aiJobService;
    }

    @GetMapping
    public ApiResponse<List<InboxVO>> list(@RequestParam(required = false) String status) {
        return ApiResponse.success(inboxService.list(status));
    }

    @PostMapping
    public ApiResponse<InboxVO> create(@Valid @RequestBody InboxCreateRequest request) {
        return ApiResponse.success(inboxService.create(request));
    }

    @PostMapping("/classify")
    public ApiResponse<Map<String, Long>> classify() {
        Map<String, Long> result = new java.util.HashMap<String, Long>();
        result.put("jobId", Long.valueOf(aiJobService.startClassifyJob()));
        return ApiResponse.success(result);
    }

    @GetMapping("/jobs/{jobId}")
    public ApiResponse<Map<String, Object>> job(@PathVariable long jobId) {
        return ApiResponse.success(aiJobService.status(jobId));
    }

    @PostMapping("/{id}/reclassify")
    public ApiResponse<InboxVO> reclassify(@PathVariable long id) {
        return ApiResponse.success(inboxService.reclassify(id));
    }

    @PostMapping("/{id}/confirm")
    public ApiResponse<ConfirmResultVO> confirm(@PathVariable long id, @Valid @RequestBody InboxConfirmRequest request) {
        return ApiResponse.success(inboxService.confirm(id, request));
    }

    @PostMapping("/confirm-high-confidence")
    public ApiResponse<List<ConfirmResultVO>> confirmHighConfidence() {
        return ApiResponse.success(inboxService.confirmHighConfidence());
    }

    @DeleteMapping("/{id}")
    public ApiResponse<Void> delete(@PathVariable long id) {
        inboxService.delete(id);
        return ApiResponse.success(null);
    }
}
