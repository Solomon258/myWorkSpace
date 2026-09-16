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
            record.origin = rs.getString("origin");
            record.parseStatus = rs.getString("parse_status");
            record.parseError = rs.getString("parse_error");
            record.rawTruncated = rs.getInt("raw_truncated") == 1;
            long sourceAttachmentId = rs.getLong("source_attachment_id");
            record.sourceAttachmentId = rs.wasNull() ? null : Long.valueOf(sourceAttachmentId);
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

    /**
     * 图片解析产出的条目：raw_content 直接就是解析出的原文，且**一次落多条**。
     *
     * <p>这里没有「先建 pending、再走 classify」两步 —— 视觉模型的输出本身就是分类结果，
     * 再让本地规则/文本模型去猜一遍只会把结果改坏。写完就是 {@code processed}（待确认）。</p>
     *
     * @param rawTruncated  原文是否被 4000 字上限截断过（截了要留痕，见 V10）
     * @param attachmentId  解析用的图片附件 id，用于失败重试与来源展示
     */
    public long insertFromImage(String raw, String source, String category, double confidence,
                                String payload, String now, boolean rawTruncated, Long attachmentId) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbcTemplate.update(connection -> {
            java.sql.PreparedStatement statement = connection.prepareStatement(
                    "INSERT INTO inbox_item(raw_content, content_type, source, status, origin,"
                            + " ai_category, ai_confidence, ai_payload, processed_at, parse_status,"
                            + " raw_truncated, source_attachment_id, created_at, updated_at, is_demo)"
                            + " VALUES (?, 'text', ?, 'processed', 'image', ?, ?, ?, ?, 'success', ?, ?, ?, ?, 0)",
                    java.sql.Statement.RETURN_GENERATED_KEYS);
            statement.setString(1, raw);
            statement.setString(2, source);
            statement.setString(3, category);
            statement.setDouble(4, confidence);
            statement.setString(5, payload);
            statement.setString(6, now);
            statement.setInt(7, rawTruncated ? 1 : 0);
            if (attachmentId == null) {
                statement.setNull(8, java.sql.Types.INTEGER);
            } else {
                statement.setLong(8, attachmentId.longValue());
            }
            statement.setString(9, now);
            statement.setString(10, now);
            return statement;
        }, keyHolder);
        return keyHolder.getKey().longValue();
    }

    /**
     * 解析进度只描述「视觉解析」这一步，与 status 各自独立（见 V10 的注释）。
     */
    public void markParseStatus(long id, String parseStatus, String parseError, String now) {
        jdbcTemplate.update("UPDATE inbox_item SET parse_status=?, parse_error=?, updated_at=? WHERE id=? AND deleted=0",
                parseStatus, parseError, now, id);
    }

    /**
     * 建一条「正在解析」的图片条目占位。
     *
     * <p>它先以 {@code status='pending'} 落库，解析成功后由
     * {@link #updateParsedFromImage} **改写**成 {@code processed}，而不是另建一条结果记录。
     * 这样用户在整理页看到的那一条，与他最终确认的是同一条 —— 否则会凭空多出一个
     * 「正在解析…」的死块，还没有任何入口能删掉它。</p>
     */
    public long insertWithOrigin(String raw, String source, Long attachmentId, String now) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbcTemplate.update(connection -> {
            java.sql.PreparedStatement statement = connection.prepareStatement(
                    "INSERT INTO inbox_item(raw_content, content_type, source, status, origin,"
                            + " parse_status, source_attachment_id, created_at, updated_at, is_demo)"
                            + " VALUES (?, 'text', ?, 'pending', 'image', 'running', ?, ?, ?, 0)",
                    java.sql.Statement.RETURN_GENERATED_KEYS);
            statement.setString(1, raw);
            statement.setString(2, source);
            if (attachmentId == null) {
                statement.setNull(3, java.sql.Types.INTEGER);
            } else {
                statement.setLong(3, attachmentId.longValue());
            }
            statement.setString(4, now);
            statement.setString(5, now);
            return statement;
        }, keyHolder);
        return keyHolder.getKey().longValue();
    }

    /** 解析成功：把占位记录改写成本条结果（见 {@link #insertWithOrigin} 的说明）。 */
    public void updateParsedFromImage(long id, String raw, String category, double confidence,
                                      String payload, boolean rawTruncated, String now) {
        jdbcTemplate.update("UPDATE inbox_item SET raw_content=?, status='processed', origin='image',"
                        + " ai_category=?, ai_confidence=?, ai_payload=?, processed_at=?,"
                        + " parse_status='success', parse_error=NULL, raw_truncated=?, updated_at=?"
                        + " WHERE id=? AND deleted=0",
                raw, category, confidence, payload, now, rawTruncated ? 1 : 0, now, id);
    }

    /**
     * 解析失败时把条目留成「待整理」：用户仍能在收集箱里看到它、手动补上文字。
     * 不调 markFailed —— 那是「整理失败」，语义不同，前端会给出不同的下一步指引。
     */
    public void markParseFailed(long id, String parseError, String now) {
        // 占位符恰好三个（parse_error / updated_at / id）—— 多传一个参数会让驱动把 id 顶掉，
        // 表现是「更新了 0 行」，而不是报错，非常难查。
        jdbcTemplate.update("UPDATE inbox_item SET status='pending', parse_status='failed', parse_error=?, updated_at=? WHERE id=? AND deleted=0",
                parseError, now, id);
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

    /**
     * 按来源标记取全部条目（含已归档）。
     *
     * <p>解析进度要用它 —— 解析中/失败的条目状态是 {@code pending}，
     * 成功的是 {@code processed}，归档后是 {@code archived}。按状态查一遍是查不全的。</p>
     */
    public List<InboxRecord> findByOrigin(String origin) {
        return jdbcTemplate.query(
                "SELECT * FROM inbox_item WHERE deleted=0 AND origin=? ORDER BY created_at DESC, id DESC",
                rowMapper, origin);
    }
}
