package com.icecode.workbench.dashboard;

import javax.validation.Valid;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.icecode.workbench.common.ApiResponse;

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

    @PostMapping("/theme")
    public ApiResponse<String> saveTheme(@Valid @RequestBody ThemeRequest request) {
        return ApiResponse.success(dashboardService.saveTheme(request.getTheme()));
    }
}
