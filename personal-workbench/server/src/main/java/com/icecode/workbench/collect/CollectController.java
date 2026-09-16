package com.icecode.workbench.collect;

import javax.validation.Valid;

import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.icecode.workbench.common.ApiResponse;

/**
 * 文章收藏入口。
 *
 * <p>当前只有「手动粘贴」这一个触发方式，但它和后续的微信回调共用
 * {@link ArticleCollectService} —— 接口形态刻意做成「一段文本进、一条笔记出」，
 * 这样后续接入入口服务或云端中转时不需要改这里。
 */
@RestController
@RequestMapping("/api/v1/collect")
public class CollectController {

    private final ArticleCollectService articleCollectService;

    public CollectController(ArticleCollectService articleCollectService) {
        this.articleCollectService = articleCollectService;
    }

    @PostMapping("/article")
    public ApiResponse<CollectResultVO> article(@Valid @RequestBody CollectRequest request) {
        return ApiResponse.success(articleCollectService.collect(request.getContent()));
    }

    /**
     * 本机 Agent 投递：Hermes / OpenClaw 收到微信里的文章链接后，转身调这里。
     *
     * <p>与 {@code /article} 是同一套逻辑，区别只是免登录 —— 调用方是同一台机器上的进程，
     * 拿不到浏览器里的登录态。免登录的安全前提（端口只绑回环地址）写在
     * {@code WebMvcConfig} 对该路径的注释里，改绑定之前必须先加鉴权。
     */
    @PostMapping("/agent")
    public ApiResponse<CollectResultVO> agent(@Valid @RequestBody CollectRequest request) {
        return ApiResponse.success(articleCollectService.collect(request.getContent()));
    }
}
