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
public class InboxRepository {

    private final JdbcTemplate jdbcTemplate;
    private final RowMapper<InboxRecord> rowMapper = new RowMapper<InboxRecord>() {
        @Override
        public InboxRecord mapRow(ResultSet rs, int rowNum) throws SQLException {
            InboxRecord record = new InboxRecord();
            record.id = rs.getLong("id");
            record.raw = rs.getString("raw_content");
            record.contentType = rs.getString("content_type");
            record.source = rs.getString("source");
            record.status = rs.getString("status");
            record.aiCategory = rs.getString("ai_category");
            double confidence = rs.getDouble("ai_confidence");
            record.aiConfidence = rs.wasNull() ? null : Double.valueOf(confidence);
            record.aiPayload = rs.getString("ai_payload");
            record.processedAt = rs.getString("processed_at");
            record.createdAt = rs.getString("created_at");
            record.updatedAt = rs.getString("updated_at");
            return record;
        }
    };

    public InboxRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public List<InboxRecord> findByStatus(String status) {
        if (status == null || status.trim().isEmpty()) {
            return jdbcTemplate.query("SELECT * FROM inbox_item WHERE deleted=0 ORDER BY created_at DESC, id DESC", rowMapper);
        }
        return jdbcTemplate.query("SELECT * FROM inbox_item WHERE deleted=0 AND status=? ORDER BY created_at DESC, id DESC", rowMapper, status);
    }

    public List<InboxRecord> findPending() {
        return jdbcTemplate.query("SELECT * FROM inbox_item WHERE deleted=0 AND status='pending' ORDER BY created_at ASC, id ASC", rowMapper);
    }

    public InboxRecord findById(long id) {
        List<InboxRecord> records = jdbcTemplate.query("SELECT * FROM inbox_item WHERE id=? AND deleted=0", rowMapper, id);
        return records.isEmpty() ? null : records.get(0);
    }

    public long insert(String raw, String contentType, String source, String now) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbcTemplate.update(connection -> {
            java.sql.PreparedStatement statement = connection.prepareStatement(
                    "INSERT INTO inbox_item(raw_content, content_type, source, status, created_at, updated_at, is_demo) VALUES (?,?,?,'pending',?,?,0)",
                    java.sql.Statement.RETURN_GENERATED_KEYS);
            statement.setString(1, raw);
            statement.setString(2, contentType);
            statement.setString(3, source);
            statement.setString(4, now);
            statement.setString(5, now);
            return statement;
        }, keyHolder);
        return keyHolder.getKey().longValue();
    }

    public void markClassified(long id, String category, double confidence, String payload, String now) {
        jdbcTemplate.update("UPDATE inbox_item SET status='processed', ai_category=?, ai_confidence=?, ai_payload=?, processed_at=?, updated_at=? WHERE id=? AND deleted=0",
                category, confidence, payload, now, now, id);
    }

    public void markFailed(long id, String now) {
        jdbcTemplate.update("UPDATE inbox_item SET status='failed', processed_at=?, updated_at=? WHERE id=? AND deleted=0", now, now, id);
    }

    public void markArchived(long id, String category, String now) {
        jdbcTemplate.update("UPDATE inbox_item SET status='archived', ai_category=?, processed_at=?, updated_at=? WHERE id=? AND deleted=0", category, now, now, id);
    }

    /** 软删除必须同时写下 deleted_at：回收站靠它算 30 天窗口（V7）。 */
    public void softDelete(long id, String now) {
        jdbcTemplate.update("UPDATE inbox_item SET deleted=1, deleted_at=?, updated_at=? WHERE id=? AND deleted=0", now, now, id);
    }

    public void insertActivity(String type, String content, String now) {
        jdbcTemplate.update("INSERT INTO activity_log(log_type, content, created_at, is_demo) VALUES (?,?,?,0)", type, content, now);
    }

    public int countPending() {
        Integer value = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM inbox_item WHERE deleted=0 AND status='pending'", Integer.class);
        return value == null ? 0 : value.intValue();
    }
}
