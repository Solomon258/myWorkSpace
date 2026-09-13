package com.icecode.workbench.inbox;

import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

@Component
public class StaleAiJobCleanup {

    private final AiJobRepository aiJobRepository;
    private final com.icecode.workbench.auth.AppConfigRepository configRepository;

    public StaleAiJobCleanup(AiJobRepository aiJobRepository,
                             com.icecode.workbench.auth.AppConfigRepository configRepository) {
        this.aiJobRepository = aiJobRepository;
        this.configRepository = configRepository;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void failStaleJobs() {
        String timezone = configRepository.findValue(com.icecode.workbench.auth.AuthConstants.CONFIG_TIMEZONE);
        if (timezone == null) timezone = "Asia/Shanghai";
        aiJobRepository.failStaleJobs(com.icecode.workbench.util.TimeUtil.now(timezone));
    }
}
