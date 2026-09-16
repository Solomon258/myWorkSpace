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

import com.icecode.workbench.attachment.AttachmentService;
import com.icecode.workbench.common.ApiResponse;
import com.icecode.workbench.vision.VisionParseService;

@RestController
@RequestMapping("/api/v1/inbox")
public class InboxController {

    private final InboxService inboxService;
    private final AiJobService aiJobService;
    private final VisionParseService visionParseService;
    private final AttachmentService attachmentService;

    public InboxController(InboxService inboxService, AiJobService aiJobService,
                           VisionParseService visionParseService, AttachmentService attachmentService) {
        this.inboxService = inboxService;
        this.aiJobService = aiJobService;
        this.visionParseService = visionParseService;
        this.attachmentService = attachmentService;
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

    /**
     * 一次确认多条。
     *
     * <p>图片解析一张图常出 2–3 条，逐条点「确认生成」时列表每次重排、很容易点错。
     * 整批一个事务：要么全成功，要么全回滚。</p>
     */
    @PostMapping("/confirm-items")
    public ApiResponse<List<ConfirmResultVO>> confirmItems(@Valid @RequestBody InboxBatchConfirmRequest request) {
        return ApiResponse.success(inboxService.confirmBatch(request));
    }

    /**
     * 触发图片解析。
     *
     * <p><b>立刻返回</b>（202 + jobId）：解析要调外部视觉模型，3–10 秒。
     * 同步等在这里会让前端整页卡住，而用户在等待期间什么都做不了。</p>
     *
     * <p>配置不全（未填视觉模型 / 开了「仅本地」）**在同步路径上就报错** ——
     * 这类失败本来就能提前知道，扔进异步任务只会变成日志里的一行，
     * 用户点完「解析」看到的是一片安静。</p>
     */
    @PostMapping("/parse-images")
    public ApiResponse<Map<String, Object>> parseImages(@Valid @RequestBody InboxParseRequest request) {
        List<Long> ids = request.getAttachmentIds();
        visionParseService.assertParseable(ids);
        // 先校一遍附件确实存在且是图片：否则「点了解析」会先返回 202，十秒后才在
        // 列表里留下一条「附件不存在」的失败条目 —— 用户白等一场，还得多删一条垃圾。
        for (Long attachmentId : ids) {
            attachmentService.openImage(attachmentId.longValue());
        }
        visionParseService.parseAsync(ids, request.getSource() == null ? "web" : request.getSource());
        Map<String, Object> result = new java.util.HashMap<String, Object>();
        result.put("accepted", Integer.valueOf(ids.size()));
        result.put("parseStatus", "running");
        return ApiResponse.success(result);
    }

    /**
     * 查询解析进度（前端轮询用）。
     *
     * <p>返回的是「最近由这批图片产出的条目」而不是一个 job 对象：
     * 用户真正关心的是「出来了哪些待确认」，只回一个百分比等于让他再点一次刷新。</p>
     */
    @GetMapping("/parse-status")
    public ApiResponse<Map<String, Object>> parseStatus() {
        return ApiResponse.success(inboxService.parseStatus());
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
