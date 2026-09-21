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
            record.grp = rs.getString("grp");
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

    /**
     * 任务列表查询。{@code sort} 决定 ORDER BY：
     * {@code updated} = 按最后修改时间（全局倒序，见下）；其它 / 为空 = 按截止时间（默认口径）。
     *
     * <p>排序放在 SQL 里而不是取回来再排：{@code LIMIT} 与 ORDER BY 是一体的 ——
     * 先按「截止时间」取前 100 条再在前端改成按修改时间排，用户看到的只是
     * 「那 100 条里改过的」，而他要的是「最近改过的那批」。两者不是一回事。</p>
     */
    public List<TaskRecord> find(String status, String priority, String grp, String keyword, String dueFrom,
                                 String dueTo, String sort, int page, int size) {
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
        // 工作 / 生活筛选（V9）。空值 = 「全部」，所以不传 grp 时不会额外收窄结果。
        if (notBlank(grp)) {
            sql.append(" AND grp = ?");
            args.add(grp);
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
        if ("updated".equals(sort)) {
            // 按最后修改时间（2026-09-20）：**不先按状态分组**，整表倒序。
            // 前端是按状态分三条泳道渲染的，状态不需要在这层排；反过来先按状态排，
            // 取到的 100 条会变成「先取办完的、再取在做的」，把用户想看的顺序打乱。
            // ⚠️ updated_at 可为空（V1 里它就是 TEXT 不带 NOT NULL），NULL 在 DESC 下会全部沉到最后，
            //    那就等于把历史任务静默踢出前 100 条 —— 用 created_at 兜底（两者同为
            //    yyyy-MM-dd HH:mm:ss 文本，字典序即时间序；回收站算保留期也是这么兜的）。
            // id DESC 是同一秒内多次修改时的 tie-breaker，保证顺序稳定、刷新不会来回跳。
            sql.append(" ORDER BY COALESCE(updated_at, created_at) DESC, id DESC LIMIT ? OFFSET ?");
        } else {
            sql.append(" ORDER BY CASE status WHEN 'doing' THEN 0 WHEN 'todo' THEN 1 WHEN 'done' THEN 2 ELSE 3 END,")
                    .append(" CASE priority WHEN 'P0' THEN 0 WHEN 'P1' THEN 1 WHEN 'P2' THEN 2 ELSE 3 END,")
                    .append(" CASE WHEN due_date IS NULL THEN 1 ELSE 0 END, due_date, created_at DESC LIMIT ? OFFSET ?");
        }
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
                    "INSERT INTO task(title, description, priority, status, due_date, is_deep_work, is_blocking, note, grp, source_inbox_id, created_at, updated_at, is_demo) VALUES (?,?,?,'todo',?,?,?,?,?,?,?,?,?)",
                    java.sql.Statement.RETURN_GENERATED_KEYS);
            statement.setString(1, request.getTitle().trim());
            statement.setString(2, trimToNull(request.getDescription()));
            statement.setString(3, request.getPriority());
            statement.setString(4, trimToNull(request.getDue()));
            statement.setInt(5, request.isDeep() ? 1 : 0);
            statement.setInt(6, request.isBlocking() ? 1 : 0);
            statement.setString(7, trimToNull(request.getNote()));
            // `grp` 必须已经在 Service 层落定（判定或校验过），Repository 只负责存。
            // 这里兜一个 'work' 是为了让「直接用 Repository 建任务」的老代码不会写进 NULL —— 见下一条注释。
            statement.setString(8, request.getGrp() == null || request.getGrp().trim().isEmpty() ? "work" : request.getGrp());
            if (sourceInboxId == null) statement.setNull(9, java.sql.Types.INTEGER);
            else statement.setLong(9, sourceInboxId.longValue());
            statement.setString(10, now);
            statement.setString(11, now);
            statement.setInt(12, demo ? 1 : 0);
            return statement;
        }, keyHolder);
        return keyHolder.getKey().longValue();
    }

    public void update(TaskRecord task) {
        jdbcTemplate.update("UPDATE task SET title=?, description=?, priority=?, due_date=?, is_deep_work=?, is_blocking=?, note=?, grp=?, updated_at=? WHERE id=? AND deleted=0",
                task.title, task.description, task.priority, task.due, task.deep ? 1 : 0,
                task.blocking ? 1 : 0, task.note, task.grp, task.updatedAt, task.id);
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

    /**
     * 「已完成」页按天汇总（2026-09-20）：只统计<b>真正完成</b>的（{@code status='done'}），
     * 按 {@code completed_at} 的日期部分分组，倒序返回。
     *
     * <p>⚠️ {@code completed_at} 在 V1 里是**可空**的（建表语句只有 {@code completed_at TEXT}），
     * 而且「已取消」的任务它恰好是 NULL（见 {@code TaskService.changeStatus}）。
     * 所以这里必须显式 {@code completed_at IS NOT NULL}：漏了它，那些 NULL 会被
     * {@code substr(NULL,1,10)} 归成一个 NULL 组，前端按日期分组时会多出一组认不出的东西。
     * 同理不能用 {@code updated_at} 兜底 —— 那会把「今天只是改过」的完成项也算成「今天完成的」，
     * 静默虚报数字，而用户在界面上完全看不出来。</p>
     *
     * <p>{@code substr(completed_at,1,10)} 依赖 {@code completed_at} 是
     * {@code yyyy-MM-dd HH:mm:ss} 文本（{@code TimeUtil.now} 的格式）—— 与
     * {@code ORDER BY COALESCE(updated_at, created_at) DESC} 靠字典序排是同一套前提。</p>
     */
    public List<DoneDayRow> findDoneByDay() {
        return jdbcTemplate.query(
                "SELECT substr(completed_at,1,10) AS day,"
                        + " COUNT(*) AS cnt,"
                        + " SUM(CASE WHEN priority='P0' THEN 1 ELSE 0 END) AS p0,"
                        + " SUM(CASE WHEN priority='P1' THEN 1 ELSE 0 END) AS p1,"
                        + " SUM(CASE WHEN is_deep_work=1 THEN 1 ELSE 0 END) AS deepcnt,"
                        + " MIN(substr(completed_at,12,5)) AS first_at,"
                        + " MAX(substr(completed_at,12,5)) AS last_at"
                        + " FROM task"
                        + " WHERE deleted=0 AND status='done' AND completed_at IS NOT NULL"
                        + " GROUP BY substr(completed_at,1,10)"
                        + " ORDER BY day DESC",
                new RowMapper<DoneDayRow>() {
                    @Override
                    public DoneDayRow mapRow(ResultSet rs, int rowNum) throws SQLException {
                        DoneDayRow row = new DoneDayRow();
                        row.day = rs.getString("day");
                        row.count = rs.getInt("cnt");
                        row.p0 = rs.getInt("p0");
                        row.p1 = rs.getInt("p1");
                        row.deep = rs.getInt("deepcnt");
                        row.firstAt = rs.getString("first_at");
                        row.lastAt = rs.getString("last_at");
                        return row;
                    }
                });
    }

    /**
     * 某一天取消的条数（2026-09-20）。取消时 {@code completed_at} 被置为 NULL，
     * 只有 {@code updated_at} 留着那一刻，所以这里按 {@code updated_at} 的日期取。
     *
     * <p>口径上的取舍：{@code updated_at} 是「最后被改动」，对一条已取消且之后再没动过的任务
     * 就等于取消时刻 —— 这是它唯一能被追溯到的位置。它单独成数、不并入完成数
     * （见 {@link TaskDoneDayVO#getCanceledCount}），所以即便偶有偏差也不会污染主数字。</p>
     */
    public int countCanceledOn(String day) {
        Integer value = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM task"
                        + " WHERE deleted=0 AND status='canceled' AND substr(COALESCE(updated_at, created_at),1,10)=?",
                Integer.class, day);
        return value == null ? 0 : value.intValue();
    }

    /** 按天汇总查询的行载体（只在本 Repository 与 {@code TaskService} 之间传递）。 */
    public static class DoneDayRow {
        public String day;
        public int count;
        public int p0;
        public int p1;
        public int deep;
        public String firstAt;
        public String lastAt;
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
