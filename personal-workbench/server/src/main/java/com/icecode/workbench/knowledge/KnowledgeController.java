package com.icecode.workbench.knowledge;

import java.util.List;
import java.util.Map;

import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.icecode.workbench.common.ApiResponse;

@RestController
@RequestMapping("/api/v1/knowledge")
public class KnowledgeController {

    private final KnowledgeService knowledgeService;
    private final AssistantService assistantService;

    public KnowledgeController(KnowledgeService knowledgeService, AssistantService assistantService) {
        this.knowledgeService = knowledgeService;
        this.assistantService = assistantService;
    }

    @GetMapping("/notes")
    public ApiResponse<List<KnowledgeNoteVO>> notes() {
        return ApiResponse.success(knowledgeService.list());
    }

    @PostMapping("/notes/{id}/sync")
    public ApiResponse<KnowledgeNoteVO> retrySync(@PathVariable long id) {
        return ApiResponse.success(knowledgeService.retrySync(id));
    }

    @DeleteMapping("/notes/{id}")
    public ApiResponse<String> delete(@PathVariable long id) {
        knowledgeService.delete(id);
        return ApiResponse.success("ok");
    }

    @GetMapping("/index")
    public ApiResponse<IndexStatusVO> indexStatus() {
        return ApiResponse.success(assistantService.indexStatus());
    }

    @PostMapping("/ask")
    public ApiResponse<AskResponseVO> ask(@org.springframework.web.bind.annotation.RequestBody AskRequest request) {
        return ApiResponse.success(assistantService.ask(request.getQuestion()));
    }

    /**
     * 把 Vault 里现成的笔记导入知识库（存量回填）。幂等，可以反复点。
     *
     * <p>body 可省略；传 {@code {"dir":"知识体系/得到"}} 只扫这个子目录，不传则扫整个 Vault。
     */
    @PostMapping("/import")
    public ApiResponse<Map<String, Object>> importFromVault(@RequestBody(required = false) Map<String, String> body) {
        String dir = body == null ? null : body.get("dir");
        return ApiResponse.success(knowledgeService.importFromVault(dir));
    }
}
