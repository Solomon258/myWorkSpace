package com.icecode.workbench.search;

import javax.validation.Valid;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.icecode.workbench.common.ApiResponse;

/**
 * 全局搜索（跨 收录 / 任务 / 日程 / 备忘 / 时间线 + 回收站）。
 *
 * <p>用 GET：纯读取、无副作用，参数都是标量。搜索<b>不写 {@code activity_log}</b> ——
 * 搜一下不是业务动作，写进去会把时间线淹掉。</p>
 *
 * <p>路由放在 {@code /api/v1/**} 下，落在 {@code LoginInterceptor} 的拦截范围内：
 * 搜索结果会露出已删记录的原文，必须和其它业务接口一样要求登录。</p>
 */
@RestController
@RequestMapping("/api/v1/search")
public class SearchController {

    private final SearchService searchService;
    private final SearchExpandService searchExpandService;

    public SearchController(SearchService searchService, SearchExpandService searchExpandService) {
        this.searchService = searchService;
        this.searchExpandService = searchExpandService;
    }

    /**
     * @param q      关键词（必填，1–100 字）
     * @param types  逗号分隔的类型过滤，缺省 = 全部。取值：task / event / memo / inbox / timeline / trash
     * @param scope  {@code all}（默认，含回收站）或 {@code active}（仅活数据）
     * @param limit  每组返回条数，默认 5，上限 20
     * @param expand 语义联想词（逗号分隔，来自 {@code /search/expand}）。命中这些词的记录会以
     *               「联想命中」的身份合并进结果，分数减半、排在精确命中之后。
     *               放在同一个接口里而不是让前端逐词再搜一遍：联想词之间是 OR 关系，
     *               逐个请求会把一条结果拆成好几份，去重和排序都没法做。
     */
    @GetMapping
    public ApiResponse<SearchResultVO> search(@RequestParam(required = false) String q,
                                              @RequestParam(required = false) String types,
                                              @RequestParam(required = false) String scope,
                                              @RequestParam(required = false) String expand,
                                              @RequestParam(defaultValue = "5") int limit) {
        return ApiResponse.success(searchService.search(q, types, scope, limit, expand));
    }

    /**
     * 语义联想：独立成接口，是为了让前端做**两段式渲染** ——
     * 关键字结果先出来（毫秒级），扩展出来的联想结果再异步补位。
     *
     * <p>它<b>不会返回错误</b>：未配置 AI、超时、模型返回垃圾，一律返回空 {@code terms} 且
     * {@code code = 0}。语义是增强不是依赖，不该让一次联想失败把搜索结果打成错误页。</p>
     */
    @PostMapping("/expand")
    public ApiResponse<SearchExpandVO> expand(@Valid @RequestBody SearchExpandRequest request) {
        return ApiResponse.success(searchExpandService.expand(request.getQ()));
    }
}
