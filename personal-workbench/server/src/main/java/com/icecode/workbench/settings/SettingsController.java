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

    /**
     * 保存 / 清除「得到登录 Cookie」。
     *
     * <p>为什么放在设置里而不是环境变量：它是用户个人的账号凭据 —— 会过期、会换号，
     * 每次都要在界面上改。塞进启动配置的话，改一次就得重启一次服务，
     * 而这条链路的使用场景恰恰是「收藏失败 → 顺手换一份 Cookie」。
     */
    @PutMapping("/dedao-cookie")
    public ApiResponse<SettingsVO> saveDedaoCookie(@Valid @RequestBody DedaoCookieRequest request) {
        return ApiResponse.success(settingsService.saveDedaoCookie(request));
    }
}
