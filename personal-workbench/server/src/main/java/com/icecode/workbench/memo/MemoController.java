package com.icecode.workbench.memo;

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
@RequestMapping("/api/v1/memos")
public class MemoController {

    private final MemoService memoService;

    public MemoController(MemoService memoService) {
        this.memoService = memoService;
    }

    @GetMapping
    public ApiResponse<List<MemoVO>> list(
            @RequestParam(required = false) String grp,
            @RequestParam(required = false) String q,
            @RequestParam(defaultValue = "false") boolean archived) {
        return ApiResponse.success(memoService.list(grp, q, archived));
    }

    @PostMapping
    public ApiResponse<MemoVO> create(@Valid @RequestBody MemoCreateRequest request) {
        return ApiResponse.success(memoService.create(request));
    }

    @PatchMapping("/{id}")
    public ApiResponse<MemoVO> update(@PathVariable long id, @Valid @RequestBody MemoUpdateRequest request) {
        return ApiResponse.success(memoService.update(id, request));
    }

    @PostMapping("/{id}/pin")
    public ApiResponse<MemoVO> togglePin(@PathVariable long id) {
        return ApiResponse.success(memoService.togglePin(id));
    }

    @PostMapping("/{id}/archive")
    public ApiResponse<MemoVO> toggleArchive(@PathVariable long id) {
        return ApiResponse.success(memoService.toggleArchive(id));
    }

    @DeleteMapping("/{id}")
    public ApiResponse<Void> delete(@PathVariable long id) {
        memoService.delete(id);
        return ApiResponse.success(null);
    }
}
