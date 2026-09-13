package com.icecode.workbench.pomodoro;

import java.util.Map;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.icecode.workbench.auth.AppConfigRepository;
import com.icecode.workbench.auth.AuthConstants;
import com.icecode.workbench.common.BizException;
import com.icecode.workbench.common.ErrorCode;
import com.icecode.workbench.util.TimeUtil;

@Service
public class PomodoroService {

    private final JdbcTemplate jdbcTemplate;
    private final AppConfigRepository configRepository;

    public PomodoroService(JdbcTemplate jdbcTemplate, AppConfigRepository configRepository) {
        this.jdbcTemplate = jdbcTemplate;
        this.configRepository = configRepository;
    }

    public PomoConfigVO getConfig() {
        Map<String, String> config = configRepository.findValues("pomo.work", "pomo.short", "pomo.long", "pomo.auto");
        return new PomoConfigVO(parseInt(config.get("pomo.work"), 25), parseInt(config.get("pomo.short"), 5),
                parseInt(config.get("pomo.long"), 15), Boolean.parseBoolean(config.get("pomo.auto")));
    }

    @Transactional(rollbackFor = Exception.class)
    public PomoConfigVO saveConfig(PomoConfigRequest request) {
        String now = now();
        configRepository.save("pomo.work", String.valueOf(request.getWork()), now);
        configRepository.save("pomo.short", String.valueOf(request.getShortBreak()), now);
        configRepository.save("pomo.long", String.valueOf(request.getLongBreak()), now);
        configRepository.save("pomo.auto", String.valueOf(request.isAuto()), now);
        return getConfig();
    }

    @Transactional(rollbackFor = Exception.class)
    public PomodoroTodayVO complete(PomodoroCreateRequest request) {
        String now = now();
        String taskTitle = null;
        if (request.getTaskId() != null) {
            taskTitle = jdbcTemplate.query(
                    "SELECT title FROM task WHERE id=? AND deleted=0",
                    rs -> rs.next() ? rs.getString(1) : null, request.getTaskId());
            if (taskTitle == null) throw new BizException(ErrorCode.RESOURCE_NOT_FOUND);
        }
        jdbcTemplate.update(connection -> {
            java.sql.PreparedStatement statement = connection.prepareStatement(
                    "INSERT INTO pomodoro(task_id, minutes, ended_at, created_at) VALUES (?,?,?,?)");
            if (request.getTaskId() == null) statement.setNull(1, java.sql.Types.BIGINT);
            else statement.setLong(1, request.getTaskId().longValue());
            statement.setInt(2, request.getMinutes().intValue());
            statement.setString(3, now);
            statement.setString(4, now);
            return statement;
        });
        String content = "完成 1 个番茄" + (taskTitle != null ? "，专注「" + taskTitle + "」" + request.getMinutes() + " 分钟"
                : "，自由专注 " + request.getMinutes() + " 分钟");
        jdbcTemplate.update("INSERT INTO activity_log(log_type, content, created_at, is_demo) VALUES ('pomo',?,?,0)", content, now);
        return today();
    }

    public PomodoroTodayVO today() {
        String today = TimeUtil.today(timezone());
        Integer count = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM pomodoro WHERE ended_at LIKE ?", Integer.class, today + "%");
        Integer minutes = jdbcTemplate.queryForObject("SELECT COALESCE(SUM(minutes),0) FROM pomodoro WHERE ended_at LIKE ?", Integer.class, today + "%");
        return new PomodoroTodayVO(count == null ? 0 : count.intValue(), minutes == null ? 0 : minutes.intValue());
    }

    private int parseInt(String value, int fallback) {
        try { return Integer.parseInt(value); } catch (Exception exception) { return fallback; }
    }

    private String timezone() {
        String timezone = configRepository.findValue(AuthConstants.CONFIG_TIMEZONE);
        return timezone == null ? "Asia/Shanghai" : timezone;
    }

    private String now() { return TimeUtil.now(timezone()); }
}
