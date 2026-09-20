package com.icecode.workbench.dashboard;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.icecode.workbench.common.ApiResponse;

/**
 * <p>驾驶舱接口。2026-09-20 起只剩 {@code GET /today}：原 {@code POST /theme}（今日主题）
 * 连同 {@code ThemeRequest} / {@code DailyPlanRepository} 一起下线 —— 用户实测这个功能没什么用，
 * 驾驶舱上填了之后除了写进时间线没有别的去处。{@code daily_plan.theme} 列保留（表本身仍被
 * {@code daily_plan_item} 的外键依赖，回收站删任务要用），只是不再有写入方。</p>
 */
@RestController
@RequestMapping("/api/v1/dashboard")
public class DashboardController {

    private final DashboardService dashboardService;

    public DashboardController(DashboardService dashboardService) {
        this.dashboardService = dashboardService;
    }

    @GetMapping("/today")
    public ApiResponse<DashboardTodayVO> today() {
        return ApiResponse.success(dashboardService.today());
    }
}
