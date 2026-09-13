package com.icecode.workbench.auth;

import javax.servlet.http.HttpServletRequest;
import javax.validation.Valid;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.icecode.workbench.common.ApiResponse;

@RestController
@RequestMapping("/api/v1/auth")
public class AuthController {

    private final AuthService authService;

    public AuthController(AuthService authService) {
        this.authService = authService;
    }

    @GetMapping("/status")
    public ApiResponse<AuthStatusVO> status(HttpServletRequest request) {
        return ApiResponse.success(authService.getStatus(request));
    }

    @PostMapping("/init")
    public ApiResponse<AuthStatusVO> initialize(@Valid @RequestBody InitRequest request,
                                                HttpServletRequest servletRequest) {
        return ApiResponse.success(authService.initialize(request, servletRequest));
    }

    @PostMapping("/login")
    public ApiResponse<AuthStatusVO> login(@Valid @RequestBody LoginRequest request,
                                           HttpServletRequest servletRequest) {
        return ApiResponse.success(authService.login(request, servletRequest));
    }

    @PostMapping("/logout")
    public ApiResponse<Void> logout(HttpServletRequest request) {
        authService.logout(request);
        return ApiResponse.success(null);
    }
}
