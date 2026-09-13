package com.icecode.workbench.settings;

import javax.servlet.http.HttpSession;
import javax.validation.Valid;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.icecode.workbench.common.ApiResponse;

@RestController
@RequestMapping("/api/v1/settings")
public class SettingsController {

    private final SettingsService settingsService;

    public SettingsController(SettingsService settingsService) {
        this.settingsService = settingsService;
    }

    @GetMapping
    public ApiResponse<SettingsVO> get() {
        return ApiResponse.success(settingsService.get());
    }

    @PutMapping("/profile")
    public ApiResponse<SettingsVO> updateProfile(@Valid @RequestBody ProfileUpdateRequest request,
                                                 HttpSession session) {
        return ApiResponse.success(settingsService.updateProfile(request, session));
    }

    @PutMapping("/ai")
    public ApiResponse<SettingsVO> saveAi(@Valid @RequestBody AiSettingsRequest request) {
        return ApiResponse.success(settingsService.saveAi(request));
    }

    @PutMapping("/obsidian")
    public ApiResponse<SettingsVO> saveObsidian(@Valid @RequestBody ObsidianSettingsRequest request) {
        return ApiResponse.success(settingsService.saveObsidian(request));
    }
}
