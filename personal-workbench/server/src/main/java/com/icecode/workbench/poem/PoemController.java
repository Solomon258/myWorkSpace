package com.icecode.workbench.poem;

import javax.validation.Valid;

import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.icecode.workbench.common.ApiResponse;

/**
 * 诗词品读接口。
 *
 * <p>只有一个 POST：{@code /api/v1/poems/reflect}。全屏展示诗词时调它生成两段文字；
 * 同一首诗第二次进入直接命中服务端缓存（响应里的 {@code cached=true}）。
 *
 * <p>路径是 {@code poems} 而不是 {@code poem}：这个模块将来可能加「诗库检索」之类的接口，
 * 复数形式留了余地。目前整个后端不认识任何一首诗 —— 诗库在前端 {@code poems.js} 里。
 */
@RestController
@RequestMapping("/api/v1/poems")
public class PoemController {

    private final PoemReflectService poemReflectService;

    public PoemController(PoemReflectService poemReflectService) {
        this.poemReflectService = poemReflectService;
    }

    @PostMapping("/reflect")
    public ApiResponse<PoemReflectVO> reflect(@Valid @RequestBody PoemReflectRequest request) {
        return ApiResponse.success(poemReflectService.reflect(request));
    }
}
