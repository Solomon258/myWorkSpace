package com.icecode.workbench.auth;

import java.nio.charset.StandardCharsets;
import java.time.DateTimeException;
import java.util.Map;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpSession;

import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.icecode.workbench.common.BizException;
import com.icecode.workbench.common.ErrorCode;
import com.icecode.workbench.demo.DemoDataInitializer;
import com.icecode.workbench.util.TimeUtil;

@Service
public class AuthService {

    private final AppConfigRepository configRepository;
    private final DemoDataInitializer demoDataInitializer;
    private final BCryptPasswordEncoder passwordEncoder = new BCryptPasswordEncoder();

    public AuthService(AppConfigRepository configRepository, DemoDataInitializer demoDataInitializer) {
        this.configRepository = configRepository;
        this.demoDataInitializer = demoDataInitializer;
    }

    public AuthStatusVO getStatus(HttpServletRequest request) {
        Map<String, String> config = configRepository.findValues(
                AuthConstants.CONFIG_INITIALIZED,
                AuthConstants.CONFIG_USERNAME,
                AuthConstants.CONFIG_TIMEZONE);
        boolean initialized = Boolean.parseBoolean(config.get(AuthConstants.CONFIG_INITIALIZED));
        HttpSession session = request.getSession(false);
        boolean loggedIn = initialized && session != null
                && session.getAttribute(AuthConstants.SESSION_USER) != null;
        String username = initialized ? config.get(AuthConstants.CONFIG_USERNAME) : null;
        String timezone = config.get(AuthConstants.CONFIG_TIMEZONE);
        return new AuthStatusVO(initialized, loggedIn, username, timezone);
    }

    @Transactional(rollbackFor = Exception.class)
    public synchronized AuthStatusVO initialize(InitRequest request, HttpServletRequest servletRequest) {
        if (isInitialized()) {
            String currentUsername = configRepository.findValue(AuthConstants.CONFIG_USERNAME);
            String currentTimezone = configRepository.findValue(AuthConstants.CONFIG_TIMEZONE);
            return new AuthStatusVO(true, isLoggedIn(servletRequest), currentUsername, currentTimezone);
        }

        validatePasswordLength(request.getPassword());
        String timezone = validateTimezone(request.getTimezone());
        String now = TimeUtil.now(timezone);
        String username = request.getUsername().trim();
        configRepository.save(AuthConstants.CONFIG_USERNAME, username, now);
        configRepository.save(AuthConstants.CONFIG_PASSWORD_HASH, passwordEncoder.encode(request.getPassword()), now);
        configRepository.save(AuthConstants.CONFIG_TIMEZONE, timezone, now);
        configRepository.save(AuthConstants.CONFIG_INITIALIZED, "true", now);

        if (request.isSeedDemo()) {
            demoDataInitializer.initializeIfNeeded(timezone);
        }
        HttpSession session = rotateSession(servletRequest);
        session.setAttribute(AuthConstants.SESSION_USER, username);
        return new AuthStatusVO(true, true, username, timezone);
    }

    public AuthStatusVO login(LoginRequest request, HttpServletRequest servletRequest) {
        if (!isInitialized()) {
            throw new BizException(ErrorCode.NOT_INITIALIZED);
        }
        String expectedUsername = configRepository.findValue(AuthConstants.CONFIG_USERNAME);
        String passwordHash = configRepository.findValue(AuthConstants.CONFIG_PASSWORD_HASH);
        if (!request.getUsername().trim().equals(expectedUsername)
                || passwordHash == null
                || !passwordEncoder.matches(request.getPassword(), passwordHash)) {
            throw new BizException(ErrorCode.INVALID_PASSWORD);
        }
        HttpSession session = rotateSession(servletRequest);
        session.setAttribute(AuthConstants.SESSION_USER, expectedUsername);
        return getStatus(servletRequest);
    }

    public void logout(HttpServletRequest request) {
        HttpSession session = request.getSession(false);
        if (session != null) {
            session.invalidate();
        }
    }

    public boolean isInitialized() {
        return Boolean.parseBoolean(configRepository.findValue(AuthConstants.CONFIG_INITIALIZED));
    }

    private boolean isLoggedIn(HttpServletRequest request) {
        HttpSession session = request.getSession(false);
        return session != null && session.getAttribute(AuthConstants.SESSION_USER) != null;
    }

    private HttpSession rotateSession(HttpServletRequest request) {
        HttpSession existing = request.getSession(false);
        if (existing != null) {
            existing.invalidate();
        }
        return request.getSession(true);
    }

    private String validateTimezone(String timezone) {
        try {
            TimeUtil.zone(timezone);
            return timezone;
        } catch (DateTimeException exception) {
            throw new BizException(ErrorCode.INVALID_TIMEZONE);
        }
    }

    private void validatePasswordLength(String password) {
        if (password.getBytes(StandardCharsets.UTF_8).length > 72) {
            throw new BizException(ErrorCode.PASSWORD_TOO_LONG);
        }
    }
}
