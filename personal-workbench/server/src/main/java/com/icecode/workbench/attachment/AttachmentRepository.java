package com.icecode.workbench.attachment;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Types;
import java.util.ArrayList;
import java.util.List;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

@Repository
public class AttachmentRepository {

    private final JdbcTemplate jdbcTemplate;

    private final RowMapper<AttachmentRecord> rowMapper = new RowMapper<AttachmentRecord>() {
        @Override
        public AttachmentRecord mapRow(ResultSet rs, int rowNum) throws SQLException {
            AttachmentRecord record = new AttachmentRecord();
            record.id = rs.getLong("id");
            record.ownerType = rs.getString("owner_type");
            long ownerId = rs.getLong("owner_id");
            record.ownerId = rs.wasNull() ? null : Long.valueOf(ownerId);
            record.fileName = rs.getString("file_name");
            record.originalName = rs.getString("original_name");
            record.mimeType = rs.getString("mime_type");
            record.byteSize = rs.getLong("byte_size");
            int width = rs.getInt("width");
            record.width = rs.wasNull() ? null : Integer.valueOf(width);
            int height = rs.getInt("height");
            record.height = rs.wasNull() ? null : Integer.valueOf(height);
            record.sha256 = rs.getString("sha256");
            record.sortOrder = rs.getInt("sort_order");
            record.createdAt = rs.getString("created_at");
            return record;
        }
    };

    public AttachmentRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public long insert(String ownerType, Long ownerId, String fileName, String originalName,
                       String mimeType, long byteSize, Integer width, Integer height,
                       String sha256, int sortOrder, String now) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbcTemplate.update(connection -> {
            PreparedStatement statement = connection.prepareStatement(
                    "INSERT INTO attachment(owner_type, owner_id, file_name, original_name, mime_type,"
                            + " byte_size, width, height, sha256, sort_order, created_at, updated_at, deleted, is_demo)"
                            + " VALUES (?,?,?,?,?,?,?,?,?,?,?,?,0,0)",
                    Statement.RETURN_GENERATED_KEYS);
            if (ownerType == null) {
                statement.setNull(1, Types.VARCHAR);
            } else {
                statement.setString(1, ownerType);
            }
            if (ownerId == null) {
                statement.setNull(2, Types.INTEGER);
            } else {
                statement.setLong(2, ownerId.longValue());
            }
            statement.setString(3, fileName);
            statement.setString(4, originalName);
            statement.setString(5, mimeType);
            statement.setLong(6, byteSize);
            if (width == null) {
                statement.setNull(7, Types.INTEGER);
            } else {
                statement.setInt(7, width.intValue());
            }
            if (height == null) {
                statement.setNull(8, Types.INTEGER);
            } else {
                statement.setInt(8, height.intValue());
            }
            statement.setString(9, sha256);
            statement.setInt(10, sortOrder);
            statement.setString(11, now);
            statement.setString(12, now);
            return statement;
        }, keyHolder);
        return keyHolder.getKey().longValue();
    }

    public List<AttachmentRecord> findByOwner(String ownerType, long ownerId) {
        return jdbcTemplate.query(
                "SELECT * FROM attachment WHERE deleted=0 AND owner_type=? AND owner_id=?"
                        + " ORDER BY sort_order ASC, id ASC",
                rowMapper, ownerType, ownerId);
    }

    /**
     * 一次查出多个归属的全部附件（列表接口专用）。
     *
     * <p>为什么必须有这个批量入口：备忘/任务/日程三个列表的卡片都要显示附件，
     * 若按记录逐条查就是 N+1 —— 一屏 100 条任务会多发 100 次查询，
     * 而 Hikari 池只有 4 个连接（见项目约定），串行排队会把列表拖慢到肉眼可见。</p>
     *
     * <p>返回结果按 {@code owner_id, sort_order, id} 排序，调用方直接顺序分组即可保持展示顺序。</p>
     */
    public List<AttachmentRecord> findByOwners(String ownerType, List<Long> ownerIds) {
        if (ownerIds == null || ownerIds.isEmpty()) {
            return new ArrayList<AttachmentRecord>();
        }
        StringBuilder placeholders = new StringBuilder();
        List<Object> args = new ArrayList<Object>();
        args.add(ownerType);
        for (Long ownerId : ownerIds) {
            if (placeholders.length() > 0) {
                placeholders.append(",");
            }
            placeholders.append("?");
            args.add(ownerId);
        }
        return jdbcTemplate.query(
                "SELECT * FROM attachment WHERE deleted=0 AND owner_type=? AND owner_id IN ("
                        + placeholders + ") ORDER BY owner_id ASC, sort_order ASC, id ASC",
                rowMapper, args.toArray());
    }

    public AttachmentRecord findById(long id) {
        List<AttachmentRecord> records = jdbcTemplate.query(
                "SELECT * FROM attachment WHERE id=? AND deleted=0", rowMapper, id);
        return records.isEmpty() ? null : records.get(0);
    }

    /** 磁盘文件是否还有活着的记录在用 —— 决定物理删文件前必须先问它。 */
    public int countActiveByFileName(String fileName) {
        Integer value = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM attachment WHERE deleted=0 AND file_name=?", Integer.class, fileName);
        return value == null ? 0 : value.intValue();
    }

    public int countByOwner(String ownerType, long ownerId) {
        Integer value = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM attachment WHERE deleted=0 AND owner_type=? AND owner_id=?",
                Integer.class, ownerType, ownerId);
        return value == null ? 0 : value.intValue();
    }

    public int nextSortOrder(String ownerType, long ownerId) {
        Integer value = jdbcTemplate.queryForObject(
                "SELECT COALESCE(MAX(sort_order), -1) + 1 FROM attachment WHERE deleted=0 AND owner_type=? AND owner_id=?",
                Integer.class, ownerType, ownerId);
        return value == null ? 0 : value.intValue();
    }

    /** 绑定时同时写 owner 与排序位；只允许从未绑定状态过来，避免抢走别人的附件。 */
    public int bindOwner(long id, String ownerType, long ownerId, int sortOrder, String now) {
        return jdbcTemplate.update(
                "UPDATE attachment SET owner_type=?, owner_id=?, sort_order=?, updated_at=?"
                        + " WHERE id=? AND deleted=0 AND owner_id IS NULL",
                ownerType, ownerId, sortOrder, now, id);
    }

    /**
     * 转绑：把某个附件从原归属挪到新归属。
     *
     * <p>{@code WHERE} 里带着原归属（{@code fromType/fromId}），这是**刻意的二次校验**：
     * 调用方先按原归属查出了这批附件，但查与改之间可能被别的请求挪走 ——
     * 条件不匹配就更新 0 行，绝不会把一个已经属于别人的附件抢过来。</p>
     *
     * <p>不能用 {@link #bindOwner} 代替：那个方法要求 {@code owner_id IS NULL}，
     * 而转绑的源记录是**有归属的**（收录条目本来持有这些附件）。</p>
     */
    public int moveOwner(long id, String fromType, long fromId, String toType, long toId,
                         int sortOrder, String now) {
        return jdbcTemplate.update(
                "UPDATE attachment SET owner_type=?, owner_id=?, sort_order=?, updated_at=?"
                        + " WHERE id=? AND deleted=0 AND owner_type=? AND owner_id=?",
                toType, toId, sortOrder, now, id, fromType, fromId);
    }

    /** 软删必须同时写 deleted_at：回收站靠它算 30 天窗口（V7 的约定）。 */
    public void softDelete(long id, String now) {
        jdbcTemplate.update(
                "UPDATE attachment SET deleted=1, deleted_at=?, updated_at=? WHERE id=? AND deleted=0",
                now, now, id);
    }

    /** 实体被删时级联软删它的附件，避免留下永远挂不上也没人清理的记录。 */
    public int softDeleteByOwner(String ownerType, long ownerId, String now) {
        return jdbcTemplate.update(
                "UPDATE attachment SET deleted=1, deleted_at=?, updated_at=?"
                        + " WHERE deleted=0 AND owner_type=? AND owner_id=?",
                now, now, ownerType, ownerId);
    }

    /** 实体从回收站恢复时，附件跟着回来。 */
    public int restoreByOwner(String ownerType, long ownerId, String now) {
        return jdbcTemplate.update(
                "UPDATE attachment SET deleted=0, deleted_at=NULL, updated_at=?"
                        + " WHERE deleted=1 AND owner_type=? AND owner_id=?",
                now, ownerType, ownerId);
    }

    /** 孤儿（上传后一直没绑定实体）且超过保留期的记录，供清理任务回收。 */
    public List<AttachmentRecord> findStaleOrphans(String cutoff, int limit) {
        return jdbcTemplate.query(
                "SELECT * FROM attachment WHERE deleted=0 AND owner_id IS NULL AND created_at<?"
                        + " ORDER BY created_at ASC LIMIT ?",
                rowMapper, cutoff, limit);
    }
}
