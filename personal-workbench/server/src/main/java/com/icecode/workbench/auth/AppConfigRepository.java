package com.icecode.workbench.auth;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class AppConfigRepository {

    private final JdbcTemplate jdbcTemplate;

    public AppConfigRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public String findValue(String key) {
        List<String> values = jdbcTemplate.queryForList(
                "SELECT config_value FROM app_config WHERE config_key = ?", String.class, key);
        return values.isEmpty() ? null : values.get(0);
    }

    public Map<String, String> findValues(String... keys) {
        Map<String, String> result = new HashMap<String, String>();
        for (String key : keys) {
            result.put(key, findValue(key));
        }
        return result;
    }

    public void save(String key, String value, String updatedAt) {
        int updated = jdbcTemplate.update(
                "UPDATE app_config SET config_value = ?, updated_at = ? WHERE config_key = ?",
                value, updatedAt, key);
        if (updated == 0) {
            jdbcTemplate.update(
                    "INSERT INTO app_config(config_key, config_value, updated_at) VALUES (?, ?, ?)",
                    key, value, updatedAt);
        }
    }
}
