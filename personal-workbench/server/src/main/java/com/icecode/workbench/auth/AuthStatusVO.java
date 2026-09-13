package com.icecode.workbench.auth;

public class AuthStatusVO {

    private final boolean initialized;
    private final boolean loggedIn;
    private final String username;
    private final String timezone;

    public AuthStatusVO(boolean initialized, boolean loggedIn, String username, String timezone) {
        this.initialized = initialized;
        this.loggedIn = loggedIn;
        this.username = username;
        this.timezone = timezone;
    }

    public boolean isInitialized() { return initialized; }
    public boolean isLoggedIn() { return loggedIn; }
    public String getUsername() { return username; }
    public String getTimezone() { return timezone; }
}
