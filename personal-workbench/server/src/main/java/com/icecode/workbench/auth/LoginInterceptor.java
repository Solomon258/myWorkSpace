package com.icecode.workbench.auth;

import java.io.IOException;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.icecode.workbench.common.ApiResponse;
import com.icecode.workbench.common.ErrorCode;

@Component
public class LoginInterceptor implements HandlerInterceptor {

    private final AuthService authService;
    private final ObjectMapper objectMapper;

    public LoginInterceptor(AuthService authService, ObjectMapper objectMapper) {
        this.authService = authService;
        this.objectMapper = objectMapper;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) throws IOException {
        if (!authService.isInitialized()) {
            writeError(response, HttpStatus.CONFLICT, ErrorCode.NOT_INITIALIZED);
            return false;
        }
        if (request.getSession(false) == null
                || request.getSession(false).getAttribute(AuthConstants.SESSION_USER) == null) {
            writeError(response, HttpStatus.UNAUTHORIZED, ErrorCode.NOT_LOGGED_IN);
            return false;
        }
        return true;
    }

    private void writeError(HttpServletResponse response, HttpStatus status, ErrorCode errorCode) throws IOException {
        response.setStatus(status.value());
        response.setCharacterEncoding("UTF-8");
        response.setContentType("application/json;charset=UTF-8");
        objectMapper.writeValue(response.getWriter(), ApiResponse.failure(errorCode));
    }
}
