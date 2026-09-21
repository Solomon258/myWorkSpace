package com.icecode.workbench.schedule;

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
@RequestMapping("/api/v1/events")
public class EventController {

    private final EventService eventService;

    public EventController(EventService eventService) {
        this.eventService = eventService;
    }

    /**
     * 三种形态共用一个入口，而不是各开一个子路径：与收藏（{@code GET /api/v1/favorites?q=}）和
     * 任务（{@code GET /api/v1/tasks?keyword=}）保持同一形态 —— 列表与检索是同一个资源。
     *
     * <ol>
     *   <li>{@code q} 非空 = 全文检索，命中范围是**全部日期**（含待定区），此时忽略 date / from / to：<br>
     *       用户既然在搜，就不会还想只看某一天或某一周。</li>
     *   <li>{@code from} + {@code to} = 区间查询（日程页周视图一次取一周），两者必须成对，
     *       只给一个由 {@code EventService.listRange} 报出能照做的中文提示。</li>
     *   <li>否则按 {@code date} 查单日，省略 date 或传空串 = 今天。</li>
     * </ol>
     *
     * <p>优先级顺序本身就是契约：检索引擎只管「找到那一条」，让 date / 区间去限制它
     * 只会制造「明明有却说没有」。返回列表里每条都带自己的 {@code date}，用户靠卡片上的
     * 日期文字判断它落在哪天。</p>
     */
    @GetMapping
    public ApiResponse<List<EventVO>> list(@RequestParam(required = false) String date,
                                           @RequestParam(required = false) String q,
                                           @RequestParam(required = false) String from,
                                           @RequestParam(required = false) String to) {
        if (q != null && !q.trim().isEmpty()) return ApiResponse.success(eventService.search(q));
        if (from != null || to != null) return ApiResponse.success(eventService.listRange(from, to));
        return ApiResponse.success(eventService.list(date));
    }

    /**
     * 待定时间区（US-4.2）。
     * 单独开一个子路径，而不是给 {@code date} 传 "pending" 这类哨兵值——
     * 哨兵值会让 date 参数同时承担「日期」和「查询模式」两种语义，日后加筛选条件必踩坑。
     */
    @GetMapping("/pending")
    public ApiResponse<List<EventVO>> pending() {
        return ApiResponse.success(eventService.listPending());
    }

    @PostMapping
    public ApiResponse<EventSaveResultVO> create(@Valid @RequestBody EventCreateRequest request) {
        return ApiResponse.success(eventService.create(request));
    }

    @PatchMapping("/{id}")
    public ApiResponse<EventSaveResultVO> update(@PathVariable long id, @Valid @RequestBody EventUpdateRequest request) {
        return ApiResponse.success(eventService.update(id, request));
    }

    @DeleteMapping("/{id}")
    public ApiResponse<Void> delete(@PathVariable long id) {
        eventService.delete(id);
        return ApiResponse.success(null);
    }
}
