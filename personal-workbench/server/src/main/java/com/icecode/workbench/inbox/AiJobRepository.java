package com.icecode.workbench.inbox;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

@Repository
public class AiJobRepository {

    private final JdbcTemplate jdbcTemplate;
    private final RowMapper<AiJobVO> rowMapper = new RowMapper<AiJobVO>() {
        @Override
        public AiJobVO mapRow(ResultSet rs, int rowNum) throws SQLException {
            long duration = rs.getLong("duration_ms");
            Long durationMs = rs.wasNull() ? null : Long.valueOf(duration);
            return new AiJobVO(rs.getLong("id"), rs.getString("status"), rs.getString("ref_id"), durationMs,
                    rs.getString("error_msg"), rs.getString("created_at"), rs.getString("updated_at"));
        }
    };

    public AiJobRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public long insertPending(String jobType, String refId, String now) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbcTemplate.update(connection -> {
            java.sql.PreparedStatement statement = connection.prepareStatement(
                    "INSERT INTO ai_job(job_type, ref_id, status, created_at, updated_at) VALUES (?,?, 'pending', ?, ?)",
                    java.sql.Statement.RETURN_GENERATED_KEYS);
            statement.setString(1, jobType);
            statement.setString(2, refId);
            statement.setString(3, now);
            statement.setString(4, now);
            return statement;
        }, keyHolder);
        return keyHolder.getKey().longValue();
    }

    public AiJobVO findById(long id) {
        List<AiJobVO> jobs = jdbcTemplate.query("SELECT * FROM ai_job WHERE id=?", rowMapper, id);
        return jobs.isEmpty() ? null : jobs.get(0);
    }

    public void markRunning(long id, String now) {
        jdbcTemplate.update("UPDATE ai_job SET status='running', updated_at=? WHERE id=?", now, id);
    }

    public void markSuccess(long id, long durationMs, String now) {
        jdbcTemplate.update("UPDATE ai_job SET status='success', duration_ms=?, updated_at=? WHERE id=?", durationMs, now, id);
    }

    public void markSuccess(long id, long durationMs, long promptTokens, long completionTokens, String now) {
        jdbcTemplate.update("UPDATE ai_job SET status='success', duration_ms=?, prompt_tokens=?, completion_tokens=?, updated_at=? WHERE id=?",
                durationMs, promptTokens, completionTokens, now, id);
    }

    public void markFailed(long id, String message, long durationMs, String now) {
        jdbcTemplate.update("UPDATE ai_job SET status='failed', error_msg=?, duration_ms=?, updated_at=? WHERE id=?", message, durationMs, now, id);
    }

    public void failStaleJobs(String now) {
        jdbcTemplate.update("UPDATE ai_job SET status='failed', error_msg='应用重启，请重新整理', updated_at=? WHERE status IN ('pending','running')", now);
    }
}
