package com.icecode.workbench.system;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import com.icecode.workbench.auth.AppConfigRepository;
import com.icecode.workbench.auth.AuthConstants;

@Component
public class DailyBackupScheduler {

    private static final Logger LOGGER = LoggerFactory.getLogger(DailyBackupScheduler.class);

    private final BackupService backupService;
    private final AppConfigRepository configRepository;

    public DailyBackupScheduler(BackupService backupService, AppConfigRepository configRepository) {
        this.backupService = backupService;
        this.configRepository = configRepository;
    }

    /** 每日 02:00（应用所在系统时区）自动备份；未完成首次配置时跳过。 */
    @Scheduled(cron = "0 0 2 * * *")
    public void dailyBackup() {
        try {
            if (!Boolean.parseBoolean(configRepository.findValue(AuthConstants.CONFIG_INITIALIZED))) return;
            BackupResultVO result = backupService.backupNow(null);
            LOGGER.info("Daily backup created: {} ({} bytes)", result.getFile(), result.getSizeBytes());
        } catch (Exception exception) {
            LOGGER.error("Daily backup failed: {}", exception.getMessage());
        }
    }
}
