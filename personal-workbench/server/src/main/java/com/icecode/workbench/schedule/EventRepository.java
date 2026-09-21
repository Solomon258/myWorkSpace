package com.icecode.workbench.schedule;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

import com.icecode.workbench.util.TimeUtil;

@Repository
public class EventRepository {

    private final JdbcTemplate jdbcTemplate;
    private final RowMapper<EventRecord> rowMapper = new RowMapper<EventRecord>() {
        @Override
        public EventRecord mapRow(ResultSet rs, int rowNum) throws SQLException {
            EventRecord record = new EventRecord();
            record.id = rs.getLong("id");
            record.title = rs.getString("title");
            record.type = rs.getString("event_type");
            record.date = rs.getString("event_date");
            record.start = rs.getString("start_time");
            record.end = rs.getString("end_time");
            long sourceInboxId = rs.getLong("source_inbox_id");
            record.sourceInboxId = rs.wasNull() ? null : Long.valueOf(sourceInboxId);
            record.createdAt = rs.getString("created_at");
            record.demo = rs.getInt("is_demo") == 1;
            // wasNull() 报告的是**最近一次** getXxx 是不是 SQL NULL，所以必须紧跟 getLong 调用，
            // 中间不能插别的取值 —— 插了就永远读到上一次的结果（这个坑静默，且很难看出来）。
            long repeatGroup = rs.getLong("repeat_group");
            record.repeatGroup = rs.wasNull() ? null : Long.valueOf(repeatGroup);
            record.repeatTotal = rs.getInt("repeat_total");
            return record;
        }
    };

    public EventRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public List<EventRecord> findByDate(String date) {
        return jdbcTemplate.query("SELECT * FROM schedule_event WHERE deleted=0 AND event_date=? ORDER BY CASE WHEN start_time IS NULL THEN 1 ELSE 0 END, start_time, id",
                rowMapper, date);
    }

    /**
     * 区间查询（日程页周视图用）：一次取回一整段日期区间，前端不必按天循环调 7 次接口。
     *
     * <p>{@code event_date IS NULL} 的待定日程**不在区间内** —— 它没有日期，不属于任何一周，
     * 仍然只能从 {@link #findPending()} 拿。这条用显式的 {@code IS NOT NULL} 写死，
     * 不依赖「{@code BETWEEN} 遇到 NULL 会判非真」这种巧合：换成人手写 {@code >= AND <=}
     * 或者日后加了 OR 条件，待定日程就会悄悄混进某一周里，而界面上看不出来。</p>
     *
     * <p>同一天内 {@code start_time} 为空的排最后，与 {@link #findByDate} 保持一致；
     * 周视图把这类卡片沉到行首是**展示层**的决定，不在 SQL 里做。</p>
     *
     * <p>YYYY-MM-DD 是定长零填充格式，字符串比较等价于日期比较，所以这里直接用 {@code >= / <=}，
     * 不需要在 SQLite 里做日期函数转换（那会让索引失效）。</p>
     */
    public List<EventRecord> findByRange(String from, String to) {
        return jdbcTemplate.query(
                "SELECT * FROM schedule_event WHERE deleted=0 AND event_date IS NOT NULL"
                        + " AND event_date>=? AND event_date<=?"
                        + " ORDER BY event_date, CASE WHEN start_time IS NULL THEN 1 ELSE 0 END, start_time, id",
                rowMapper, from, to);
    }

    /** 待定时间区：日期还没定下来的日程（US-4.2）。按创建顺序排列，人工补全时从最早的开始。 */
    public List<EventRecord> findPending() {
        return jdbcTemplate.query("SELECT * FROM schedule_event WHERE deleted=0 AND event_date IS NULL ORDER BY id",
                rowMapper);
    }

    public int countPending() {
        Integer value = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM schedule_event WHERE deleted=0 AND event_date IS NULL", Integer.class);
        return value == null ? 0 : value.intValue();
    }

    /**
     * 全文检索：跨**所有日期**（含日期未定的待定区）按标题与时间段模糊匹配。
     *
     * <p>为什么跨日期：日程页是按日视图，用户在这个页签里搜索时往往并不知道那条日程落在哪一天
     * （「上个月那个和客户的会」），只搜「正在看的那天」会出现「搜了但没有」——
     * 那比不提供搜索更糟，用户会以为这条日程不存在。所以命中范围是库里的全部日程，
     * 结果里靠卡片自身的日期文字告诉用户它在哪天。</p>
     *
     * <p>为什么不搜「类型」：类型存的是 {@code meeting / deep_block / other} 这三个英文枚举，
     * 中文名目前有两个副本（TransferService 与 TrashService 各一份）。在 SQL 里再抄第三份，
     * 三处一旦漂移就会「搜『会议』搜不到会议」，而且不报错。类型本来就只有三个取值，
     * 按它检索等于筛掉三分之二，那是「筛选」不是「检索」。</p>
     *
     * <p>待定日程（{@code event_date IS NULL}）排在最后，但**必须包含在内**：它同样是用户记下来的事，
     * 只是还没排进某一天。</p>
     */
    public List<EventRecord> search(String keyword) {
        String like = "%" + escapeLike(keyword.trim().toLowerCase()) + "%";
        return jdbcTemplate.query(
                "SELECT * FROM schedule_event WHERE deleted=0 AND ("
                        + "LOWER(title) LIKE ? ESCAPE '\\'"
                        + " OR LOWER(COALESCE(start_time,'')) LIKE ? ESCAPE '\\'"
                        + " OR LOWER(COALESCE(end_time,'')) LIKE ? ESCAPE '\\')"
                        + " ORDER BY CASE WHEN event_date IS NULL THEN 1 ELSE 0 END, event_date,"
                        + " CASE WHEN start_time IS NULL THEN 1 ELSE 0 END, start_time, id",
                rowMapper, like, like, like);
    }

    public EventRecord findById(long id) {
        List<EventRecord> records = jdbcTemplate.query("SELECT * FROM schedule_event WHERE id=? AND deleted=0", rowMapper, id);
        return records.isEmpty() ? null : records.get(0);
    }

    public long insert(String title, String type, String date, String start, String end, String now) {
        return insert(title, type, date, start, end, now, false, null);
    }

    /**
     * 带来源标记的插入（「移至」用）。
     *
     * <p>{@code demo} 决定「清空示例数据」能不能清掉它，{@code sourceInboxId} 让移过来的日程
     * 仍然指得回它的收录来源。理由见 {@code TaskRepository.insert} 上的说明。</p>
     */
    public long insert(String title, String type, String date, String start, String end, String now,
                       boolean demo, Long sourceInboxId) {
        return insert(title, type, date, start, end, now, demo, sourceInboxId, null, 1);
    }

    /**
     * 带重复分组信息的插入（「每周 X」这类周期日程，见 V8 迁移）。
     *
     * <p>注意 {@code repeatGroup} 的值是**组内首条记录的 id**，插第一期之前根本拿不到它，
     * 所以调用顺序是：先插第一期（group 传 null）→ 拿到 id → {@link #markRepeatGroup} 回填 →
     * 再用这个 id 当 group 插第二期及以后。这里不「自动帮首期补 group」，
     * 因为那会把「谁是这个组的开始」藏进数据访问层，调用方反而看不清顺序。</p>
     */
    public long insert(String title, String type, String date, String start, String end, String now,
                       boolean demo, Long sourceInboxId, Long repeatGroup, int repeatTotal) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbcTemplate.update(connection -> {
            java.sql.PreparedStatement statement = connection.prepareStatement(
                    "INSERT INTO schedule_event(title, event_type, event_date, start_time, end_time, source_inbox_id, repeat_group, repeat_total, created_at, updated_at, is_demo) VALUES (?,?,?,?,?,?,?,?,?,?,?)",
                    java.sql.Statement.RETURN_GENERATED_KEYS);
            statement.setString(1, title);
            statement.setString(2, type);
            statement.setString(3, date);
            statement.setString(4, start);
            statement.setString(5, end);
            if (sourceInboxId == null) statement.setNull(6, java.sql.Types.INTEGER);
            else statement.setLong(6, sourceInboxId.longValue());
            if (repeatGroup == null) statement.setNull(7, java.sql.Types.INTEGER);
            else statement.setLong(7, repeatGroup.longValue());
            statement.setInt(8, repeatTotal);
            statement.setString(9, now);
            statement.setString(10, now);
            statement.setInt(11, demo ? 1 : 0);
            return statement;
        }, keyHolder);
        return keyHolder.getKey().longValue();
    }

    /**
     * 把某一期标记成「它所在重复组的第几期」。
     *
     * <p>单独一个方法是因为 group 的值**就是首期的 id** —— 插入前拿不到，只能插完再回填。
     * 只更新这两列，不碰其它字段：这个动作发生在「刚插完第一期」的瞬间，
     * 任何多余的 SET 都可能把调用方刚写进去的值覆盖掉。</p>
     */
    public void markRepeatGroup(long id, int total) {
        jdbcTemplate.update("UPDATE schedule_event SET repeat_group=?, repeat_total=? WHERE id=?", id, total, id);
    }

    /**
     * 写入一条日程；{@code weeks > 1} 时**物化**成 N 条「每周同一天、同一时刻」的记录，返回首期 id。
     *
     * <p>为什么放在数据访问层：手工新建（{@code EventService}）与收录确认（{@code InboxService}）
     * 是两条完全不同的写入路径，各写一份循环迟早会漂 —— 而漂了之后的表现是
     * 「收录进来的重复日程只生成一期」这种只在某一个入口出现的怪事，很难往「两处实现不一致」上想。
     * 让「物化」只有一处实现，这个坑就不存在。</p>
     *
     * <p>为什么物化而不是「一条记录 + 查询时展开」：用户要求「只改其中某一次」也能生效
     * （2026-09-13 确认）。展开方案要额外存「哪一期被单独改过」的例外，查询时再合并主线与例外，
     * 复杂度不成比例。物化之后每一期就是一条普通日程：改一条只影响一条，
     * 而且周视图 / 驾驶舱今日日程 / 冲突检测 / 全文检索 / 回收站**全都不用改**。</p>
     *
     * <p>组标识取**首期的 id**：插第一期之前拿不到它，所以插完再回填；后续各期直接带上这个值。</p>
     */
    public long insertRepeating(String title, String type, String date, String start, String end, String now,
                                boolean demo, Long sourceInboxId, int weeks) {
        int total = Math.max(1, weeks);
        long firstId = insert(title, type, date, start, end, now, demo, sourceInboxId, null, total);
        if (total <= 1) return firstId;
        markRepeatGroup(firstId, total);
        java.time.LocalDate base = java.time.LocalDate.parse(date);
        for (int i = 1; i < total; i++) {
            insert(title, type, TimeUtil.format(base.plusWeeks(i)), start, end, now, demo, sourceInboxId,
                    Long.valueOf(firstId), total);
        }
        return firstId;
    }

    public void update(EventRecord event, String updatedAt) {
        jdbcTemplate.update("UPDATE schedule_event SET title=?, event_type=?, event_date=?, start_time=?, end_time=?, updated_at=? WHERE id=? AND deleted=0",
                event.title, event.type, event.date, event.start, event.end, updatedAt, event.id);
    }

    /** 软删除必须同时写下 deleted_at：回收站靠它算 30 天窗口（V7）。 */
    public void softDelete(long id, String updatedAt) {
        jdbcTemplate.update("UPDATE schedule_event SET deleted=1, deleted_at=?, updated_at=? WHERE id=? AND deleted=0", updatedAt, updatedAt, id);
    }

    public int countByDate(String date) {
        Integer value = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM schedule_event WHERE deleted=0 AND event_date=?", Integer.class, date);
        return value == null ? 0 : value.intValue();
    }

    public void insertActivity(String type, String content, String now) {
        jdbcTemplate.update("INSERT INTO activity_log(log_type, content, created_at, is_demo) VALUES (?,?,?,0)", type, content, now);
    }

    /**
     * 用户输入里的通配符必须当成普通字符：带通配符的 LIKE 会把「搜 %」变成「搜全部」，
     * 用户看到的是「搜什么都返回全部」，而日志里没有任何异常。
     * 与 {@code TaskRepository} / {@code FavoriteRepository} 里的同名方法一致。
     */
    private static String escapeLike(String value) {
        return value.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
    }
}
