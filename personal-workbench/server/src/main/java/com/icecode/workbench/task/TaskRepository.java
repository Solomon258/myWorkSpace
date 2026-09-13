package com.icecode.workbench.task;

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
public class TaskRepository {

    private final JdbcTemplate jdbcTemplate;
    private final RowMapper<TaskRecord> rowMapper = new RowMapper<TaskRecord>() {
        @Override
        public TaskRecord mapRow(ResultSet rs, int rowNum) throws SQLException {
            TaskRecord record = new TaskRecord();
            record.id = rs.getLong("id");
            record.title = rs.getString("title");
            record.description = rs.getString("description");
            record.priority = rs.getString("priority");
            record.status = rs.getString("status");
            record.due = rs.getString("due_date");
            record.deep = rs.getInt("is_deep_work") == 1;
            record.blocking = rs.getInt("is_blocking") == 1;
            record.note = rs.getString("note");
            record.postponed = rs.getInt("postponed");
            long sourceInboxId = rs.getLong("source_inbox_id");
            record.sourceInboxId = rs.wasNull() ? null : Long.valueOf(sourceInboxId);
            record.completedAt = rs.getString("completed_at");
            record.createdAt = rs.getString("created_at");
            record.updatedAt = rs.getString("updated_at");
            record.demo = rs.getInt("is_demo") == 1;
            return record;
        }
    };

    public TaskRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public List<TaskRecord> find(String status, String priority, String keyword, String dueFrom,
                                 String dueTo, int page, int size) {
        StringBuilder sql = new StringBuilder("SELECT * FROM task WHERE deleted = 0");
        List<Object> args = new ArrayList<Object>();
        if (notBlank(status)) {
            sql.append(" AND status = ?");
            args.add(status);
        }
        if (notBlank(priority)) {
            sql.append(" AND priority = ?");
            args.add(priority);
        }
        if (notBlank(keyword)) {
            sql.append(" AND (LOWER(title) LIKE ? ESCAPE '\\' OR LOWER(COALESCE(description,'')) LIKE ? ESCAPE '\\' OR LOWER(COALESCE(note,'')) LIKE ? ESCAPE '\\')");
            String like = "%" + escapeLike(keyword.toLowerCase()) + "%";
            args.add(like);
            args.add(like);
            args.add(like);
        }
        if (notBlank(dueFrom)) {
            sql.append(" AND due_date >= ?");
            args.add(dueFrom);
        }
        if (notBlank(dueTo)) {
            sql.append(" AND due_date <= ?");
            args.add(dueTo);
        }
        sql.append(" ORDER BY CASE status WHEN 'doing' THEN 0 WHEN 'todo' THEN 1 WHEN 'done' THEN 2 ELSE 3 END,")
                .append(" CASE priority WHEN 'P0' THEN 0 WHEN 'P1' THEN 1 WHEN 'P2' THEN 2 ELSE 3 END,")
                .append(" CASE WHEN due_date IS NULL THEN 1 ELSE 0 END, due_date, created_at DESC LIMIT ? OFFSET ?");
        args.add(Integer.valueOf(size));
        args.add(Integer.valueOf((page - 1) * size));
        return jdbcTemplate.query(sql.toString(), rowMapper, args.toArray());
    }

    public List<TaskRecord> findOpenTasks() {
        return jdbcTemplate.query("SELECT * FROM task WHERE deleted = 0 AND status IN ('todo','doing')", rowMapper);
    }

    public TaskRecord findById(long id) {
        List<TaskRecord> records = jdbcTemplate.query("SELECT * FROM task WHERE id = ? AND deleted = 0", rowMapper, id);
        return records.isEmpty() ? null : records.get(0);
    }

    public long insert(TaskCreateRequest request, String now) {
        return insert(request, now, false, null);
    }

    /**
     * 带来源标记的插入（「移至」用）。
     *
     * <p>必须能带 {@code is_demo}：从示例数据移过来的记录若不继承这个标记，
     * 「清空示例数据」就清不掉它 —— 用户点了清空、提示说已清空 N 条，列表里却还剩一条，
     * 只会怀疑功能坏了。</p>
     *
     * <p>{@code source_inbox_id} 同理保留，移过来的任务仍然指得回它的收录来源。</p>
     */
    public long insert(TaskCreateRequest request, String now, boolean demo, Long sourceInboxId) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbcTemplate.update(connection -> {
            java.sql.PreparedStatement statement = connection.prepareStatement(
                    "INSERT INTO task(title, description, priority, status, due_date, is_deep_work, is_blocking, note, source_inbox_id, created_at, updated_at, is_demo) VALUES (?,?,?,'todo',?,?,?,?,?,?,?,?)",
                    java.sql.Statement.RETURN_GENERATED_KEYS);
            statement.setString(1, request.getTitle().trim());
            statement.setString(2, trimToNull(request.getDescription()));
            statement.setString(3, request.getPriority());
            statement.setString(4, trimToNull(request.getDue()));
            statement.setInt(5, request.isDeep() ? 1 : 0);
            statement.setInt(6, request.isBlocking() ? 1 : 0);
            statement.setString(7, trimToNull(request.getNote()));
            if (sourceInboxId == null) statement.setNull(8, java.sql.Types.INTEGER);
            else statement.setLong(8, sourceInboxId.longValue());
            statement.setString(9, now);
            statement.setString(10, now);
            statement.setInt(11, demo ? 1 : 0);
            return statement;
        }, keyHolder);
        return keyHolder.getKey().longValue();
    }

    public void update(TaskRecord task) {
        jdbcTemplate.update("UPDATE task SET title=?, description=?, priority=?, due_date=?, is_deep_work=?, is_blocking=?, note=?, updated_at=? WHERE id=? AND deleted=0",
                task.title, task.description, task.priority, task.due, task.deep ? 1 : 0,
                task.blocking ? 1 : 0, task.note, task.updatedAt, task.id);
    }

    public void updateStatus(long id, String status, String completedAt, String updatedAt) {
        jdbcTemplate.update("UPDATE task SET status=?, completed_at=?, updated_at=? WHERE id=? AND deleted=0",
                status, completedAt, updatedAt, id);
    }

    public void postpone(long id, String due, int postponed, String updatedAt) {
        jdbcTemplate.update("UPDATE task SET due_date=?, postponed=?, updated_at=? WHERE id=? AND deleted=0",
                due, postponed, updatedAt, id);
    }

    /** 软删除必须同时写下 deleted_at：回收站靠它算 30 天窗口（V7）。 */
    public void softDelete(long id, String updatedAt) {
        jdbcTemplate.update("UPDATE task SET deleted=1, deleted_at=?, updated_at=? WHERE id=? AND deleted=0", updatedAt, updatedAt, id);
    }

    public int countAllActive() {
        Integer value = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM task WHERE deleted=0", Integer.class);
        return value == null ? 0 : value.intValue();
    }

    public int countDone() {
        Integer value = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM task WHERE deleted=0 AND status='done'", Integer.class);
        return value == null ? 0 : value.intValue();
    }

    public void insertActivity(String type, String content, String now) {
        jdbcTemplate.update("INSERT INTO activity_log(log_type, content, created_at, is_demo) VALUES (?,?,?,0)", type, content, now);
    }

    private static boolean notBlank(String value) {
        return value != null && !value.trim().isEmpty();
    }

    private static String trimToNull(String value) {
        if (value == null) return null;
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    private static String escapeLike(String value) {
        return value.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
    }
}
