package com.icecode.workbench.dashboard;

import java.util.List;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class DailyPlanRepository {

    private final JdbcTemplate jdbcTemplate;

    public DailyPlanRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public String findTheme(String planDate) {
        List<String> themes = jdbcTemplate.queryForList(
                "SELECT theme FROM daily_plan WHERE plan_date=?", String.class, planDate);
        return themes.isEmpty() ? null : themes.get(0);
    }

    public void saveTheme(String planDate, String theme, String now) {
        int updated = jdbcTemplate.update(
                "UPDATE daily_plan SET theme=?, updated_at=? WHERE plan_date=?", theme, now, planDate);
        if (updated == 0) {
            jdbcTemplate.update("INSERT INTO daily_plan(plan_date, theme, created_at, updated_at) VALUES (?,?,?,?)",
                    planDate, theme, now, now);
        }
    }

    public void insertActivity(String type, String content, String now) {
        jdbcTemplate.update("INSERT INTO activity_log(log_type, content, created_at, is_demo) VALUES (?,?,?,0)", type, content, now);
    }
}
