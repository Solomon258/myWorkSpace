package com.icecode.workbench.memo;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

@Repository
public class MemoRepository {

    private final JdbcTemplate jdbcTemplate;
    private final RowMapper<MemoRecord> rowMapper = new RowMapper<MemoRecord>() {
        @Override
        public MemoRecord mapRow(ResultSet rs, int rowNum) throws SQLException {
            MemoRecord record = new MemoRecord();
            record.id = rs.getLong("id");
            record.title = rs.getString("title");
            record.content = rs.getString("content");
            record.url = rs.getString("url");
            record.tags = rs.getString("tags");
            record.grp = rs.getString("grp");
            record.pinned = rs.getInt("pinned") == 1;
            record.status = rs.getString("status");
            long sourceInboxId = rs.getLong("source_inbox_id");
            record.sourceInboxId = rs.wasNull() ? null : Long.valueOf(sourceInboxId);
            record.createdAt = rs.getString("created_at");
            record.updatedAt = rs.getString("updated_at");
            record.demo = rs.getInt("is_demo") == 1;
            return record;
        }
    };

    public MemoRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public List<MemoRecord> find(String grp, String keyword, boolean includeArchived) {
        StringBuilder sql = new StringBuilder("SELECT * FROM memo WHERE deleted=0");
        List<Object> args = new ArrayList<Object>();
        if (!includeArchived) sql.append(" AND status='active'");
        if (grp != null && !grp.trim().isEmpty()) {
            sql.append(" AND grp=?");
            args.add(grp);
        }
        if (keyword != null && !keyword.trim().isEmpty()) {
            sql.append(" AND (LOWER(title) LIKE ? ESCAPE '\\' OR LOWER(COALESCE(content,'')) LIKE ? ESCAPE '\\' OR LOWER(COALESCE(tags,'')) LIKE ? ESCAPE '\\' OR LOWER(COALESCE(url,'')) LIKE ? ESCAPE '\\')");
            String like = "%" + escapeLike(keyword.trim().toLowerCase()) + "%";
            args.add(like);
            args.add(like);
            args.add(like);
            args.add(like);
        }
        sql.append(" ORDER BY pinned DESC, updated_at DESC, id DESC");
        return jdbcTemplate.query(sql.toString(), rowMapper, args.toArray());
    }

    public MemoRecord findById(long id) {
        List<MemoRecord> records = jdbcTemplate.query("SELECT * FROM memo WHERE id=? AND deleted=0", rowMapper, id);
        return records.isEmpty() ? null : records.get(0);
    }

    public long insert(String title, String content, String url, String tags, String grp, String now) {
        return insert(title, content, url, tags, grp, now, false, null);
    }

    /**
     * 带来源标记的插入（「移至」用）。
     *
     * <p>理由见 {@code TaskRepository.insert} 上的说明：{@code demo} 决定「清空示例数据」
     * 能不能清掉它，{@code sourceInboxId} 保留收录来源。</p>
     */
    public long insert(String title, String content, String url, String tags, String grp, String now,
                       boolean demo, Long sourceInboxId) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbcTemplate.update(connection -> {
            java.sql.PreparedStatement statement = connection.prepareStatement(
                    "INSERT INTO memo(title, content, url, tags, grp, pinned, status, source_inbox_id, created_at, updated_at, is_demo) VALUES (?,?,?,?,?,0,'active',?,?,?,?)",
                    java.sql.Statement.RETURN_GENERATED_KEYS);
            statement.setString(1, title);
            statement.setString(2, content);
            statement.setString(3, url);
            statement.setString(4, tags);
            statement.setString(5, grp);
            if (sourceInboxId == null) statement.setNull(6, java.sql.Types.INTEGER);
            else statement.setLong(6, sourceInboxId.longValue());
            statement.setString(7, now);
            statement.setString(8, now);
            statement.setInt(9, demo ? 1 : 0);
            return statement;
        }, keyHolder);
        return keyHolder.getKey().longValue();
    }

    public void update(MemoRecord memo) {
        jdbcTemplate.update("UPDATE memo SET title=?, content=?, url=?, tags=?, grp=?, updated_at=? WHERE id=? AND deleted=0",
                memo.title, memo.content, memo.url, memo.tags, memo.grp, memo.updatedAt, memo.id);
    }

    public void setPinned(long id, boolean pinned, String updatedAt) {
        jdbcTemplate.update("UPDATE memo SET pinned=?, updated_at=? WHERE id=? AND deleted=0", pinned ? 1 : 0, updatedAt, id);
    }

    public void setStatus(long id, String status, String updatedAt) {
        jdbcTemplate.update("UPDATE memo SET status=?, updated_at=? WHERE id=? AND deleted=0", status, updatedAt, id);
    }

    /** 软删除必须同时写下 deleted_at：回收站靠它算 30 天窗口（V7）。 */
    public void softDelete(long id, String updatedAt) {
        jdbcTemplate.update("UPDATE memo SET deleted=1, deleted_at=?, updated_at=? WHERE id=? AND deleted=0", updatedAt, updatedAt, id);
    }

    public void insertActivity(String type, String content, String now) {
        jdbcTemplate.update("INSERT INTO activity_log(log_type, content, created_at, is_demo) VALUES (?,?,?,0)", type, content, now);
    }

    public void insertActivity(String type, String category, String content, String now) {
        jdbcTemplate.update("INSERT INTO activity_log(log_type, category, content, created_at, is_demo) VALUES (?,?,?,?,0)",
                type, category, content, now);
    }

    public int countByGroup(String grp, boolean includeArchived) {
        StringBuilder sql = new StringBuilder("SELECT COUNT(*) FROM memo WHERE deleted=0");
        List<Object> args = new ArrayList<Object>();
        if (!includeArchived) sql.append(" AND status='active'");
        if (grp != null) { sql.append(" AND grp=?"); args.add(grp); }
        Integer value = jdbcTemplate.queryForObject(sql.toString(), Integer.class, args.toArray());
        return value == null ? 0 : value.intValue();
    }

    private static String escapeLike(String value) {
        return value.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
    }
}
