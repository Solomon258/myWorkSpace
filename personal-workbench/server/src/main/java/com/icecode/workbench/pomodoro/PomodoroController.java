package com.icecode.workbench.pomodoro;

import javax.validation.Valid;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.icecode.workbench.common.ApiResponse;

@RestController
@RequestMapping("/api/v1/pomodoros")
public class PomodoroController {

    private final PomodoroService pomodoroService;

    public PomodoroController(PomodoroService pomodoroService) {
        this.pomodoroService = pomodoroService;
    }

    @GetMapping("/config")
    public ApiResponse<PomoConfigVO> config() {
        return ApiResponse.success(pomodoroService.getConfig());
    }

    @PutMapping("/config")
    public ApiResponse<PomoConfigVO> saveConfig(@Valid @RequestBody PomoConfigRequest request) {
        return ApiResponse.success(pomodoroService.saveConfig(request));
    }

    @GetMapping("/today")
    public ApiResponse<PomodoroTodayVO> today() {
        return ApiResponse.success(pomodoroService.today());
    }

    @PostMapping
    public ApiResponse<PomodoroTodayVO> complete(@Valid @RequestBody PomodoroCreateRequest request) {
        return ApiResponse.success(pomodoroService.complete(request));
    }
}
