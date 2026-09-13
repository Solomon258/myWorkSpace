package com.icecode.workbench.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Arrays;
import java.util.List;
import java.util.UUID;

import javax.sql.DataSource;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.sqlite.SQLiteException;
import org.springframework.jdbc.UncategorizedSQLException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

@SpringBootTest
class SchemaMigrationTest {

    private static final String TEST_ROOT = "target/中文路径测试/schema-" + UUID.randomUUID().toString();

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("workbench.data-dir", () -> TEST_ROOT + "/data");
        registry.add("workbench.home-dir", () -> TEST_ROOT);
    }

    @Autowired
    private DataSource dataSource;

    @Test
    void createsAllBusinessTablesAndFlywayHistory() {
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        List<String> tables = jdbc.queryForList(
                "SELECT name FROM sqlite_master WHERE type='table' AND name NOT LIKE 'sqlite_%' ORDER BY name",
                String.class);

        assertThat(tables).containsAll(Arrays.asList(
                "activity_log", "ai_job", "app_config", "daily_plan", "daily_plan_item",
                "flyway_schema_history", "inbox_item", "knowledge_note", "memo", "pomodoro",
                "schedule_event", "task", "wechat_msg_log"));
        assertThat(tables).hasSize(13);
    }

    @Test
    void appliesAllConnectionPragmas() {
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        Integer foreignKeys = jdbc.queryForObject("PRAGMA foreign_keys", Integer.class);
        String journalMode = jdbc.queryForObject("PRAGMA journal_mode", String.class);
        Integer busyTimeout = jdbc.queryForObject("PRAGMA busy_timeout", Integer.class);
        Integer synchronous = jdbc.queryForObject("PRAGMA synchronous", Integer.class);

        assertThat(foreignKeys).isEqualTo(1);
        assertThat(journalMode).isEqualToIgnoringCase("wal");
        assertThat(busyTimeout).isEqualTo(5000);
        assertThat(synchronous).isEqualTo(1);
    }

    @Test
    void rejectsInvalidForeignKey() {
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);

        org.assertj.core.api.Assertions.assertThatThrownBy(() -> jdbc.update(
                "INSERT INTO task(title, source_inbox_id, created_at) VALUES (?, ?, ?)",
                "invalid foreign key", 999999L, "2026-09-07 14:00:00"))
                .isInstanceOf(UncategorizedSQLException.class)
                .hasRootCauseInstanceOf(SQLiteException.class)
                .hasMessageContaining("FOREIGN KEY constraint failed");
    }

    @Test
    void rejectsInvalidTaskPriority() {
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);

        org.assertj.core.api.Assertions.assertThatThrownBy(() -> jdbc.update(
                "INSERT INTO task(title, priority, created_at) VALUES (?, ?, ?)",
                "invalid priority", "P9", "2026-09-07 14:00:00"))
                .isInstanceOf(UncategorizedSQLException.class)
                .hasRootCauseInstanceOf(SQLiteException.class)
                .hasMessageContaining("chk_task_priority");
    }

    @Test
    void createsExpectedIndexes() {
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        List<String> indexes = jdbc.queryForList(
                "SELECT name FROM sqlite_master WHERE type='index' AND name NOT LIKE 'sqlite_%' ORDER BY name",
                String.class);

        assertThat(indexes).contains(
                "idx_inbox_status", "idx_task_status_priority", "idx_task_due", "idx_event_date",
                "idx_memo_status", "idx_pomo_ended", "idx_log_created", "idx_plan_item",
                "idx_task_deleted", "idx_memo_deleted", "idx_inbox_deleted", "idx_ai_job_status",
                // V6 整表重建 schedule_event 时只还原了 idx_event_date / idx_event_demo，
                // 把 deleted 索引漏了；V7 补齐。回收站按 deleted=1 过滤，缺它就得全表扫。
                "idx_event_deleted");
    }

    @Test
    void seedsRequiredConfigurationKeys() {
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM app_config WHERE config_key IN "
                        + "('app.initialized','app.username','app.password_hash','app.timezone')",
                Integer.class);
        assertThat(count).isEqualTo(4);
    }

    @Test
    void recordsAllMigrationsExactlyOnce() {
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM flyway_schema_history WHERE success = 1 AND version IN ('1', '2', '3', '4', '5', '6', '7', '8')",
                Integer.class);
        assertThat(count).isEqualTo(8);
    }

    /**
     * V8 的核心意图：让「每周 X」这类周期日程能一次占住连续几周。
     *
     * <p>物化成 N 条独立记录（而不是一条记录 + 查询时展开），是为了让「只改其中某一次」天然成立 ——
     * 一旦这条路径退化成「共享一行」，用户改第 2 周的时间会连带改掉第 3、4 周，
     * 而那正是本次需求明确排除的行为。</p>
     */
    @Test
    void keepsRepeatColumnsIndependentPerOccurrence() {
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        // 固定时间戳：这一条测的是「重复列各自的独立性」，与创建时间无关；
        // 用字面量就不必为一个时间戳把 TimeUtil 引进这个测试类。
        String now = "2026-09-13 09:00:00";
        jdbc.update("INSERT INTO schedule_event(title,event_type,event_date,start_time,end_time,created_at,updated_at,is_demo,repeat_group,repeat_total)"
                + " VALUES (?,?,?,?,?,?,?,0,?,?)", "周会", "meeting", "2026-09-16", "09:00", "10:00", now, now, 7L, 4);
        jdbc.update("INSERT INTO schedule_event(title,event_type,event_date,start_time,end_time,created_at,updated_at,is_demo,repeat_group,repeat_total)"
                + " VALUES (?,?,?,?,?,?,?,0,?,?)", "周会", "meeting", "2026-09-23", "09:00", "10:00", now, now, 7L, 4);

        // 改其中一期的时间，另一期必须原样不动
        jdbc.update("UPDATE schedule_event SET start_time='14:00' WHERE event_date='2026-09-23' AND repeat_group=7");
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM schedule_event WHERE repeat_group=7 AND start_time='14:00'", Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM schedule_event WHERE repeat_group=7 AND start_time='09:00'", Integer.class)).isEqualTo(1);

        // 不重复的日程 repeat_group 为 NULL、repeat_total 为默认的 1
        jdbc.update("INSERT INTO schedule_event(title,event_type,event_date,created_at,updated_at,is_demo)"
                + " VALUES (?,?,?,?,?,0)", "单次日程", "other", "2026-09-16", now, now);
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM schedule_event WHERE title='单次日程' AND repeat_group IS NULL AND repeat_total=1",
                Integer.class)).isEqualTo(1);
    }

    /**
     * V7 的核心意图：给五张软删表加「删除时间」，并保证「有删除时间 ⇒ 必然处于已删除状态」。
     *
     * <p>没有这条约束的话，一次「删除后又恢复」只要漏清 deleted_at（或被别处的
     * {@code UPDATE ... SET updated_at=?} 冲掉），回收站里就会出现一条
     * 列表可见、点恢复却报「没有被删除」的幽灵记录。</p>
     */
    @Test
    void keepsDeletedAtNullUnlessTheRowIsDeleted() {
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);

        for (String table : new String[] {"inbox_item", "task", "schedule_event", "memo", "knowledge_note"}) {
            assertThat(jdbc.queryForList(
                    "SELECT name FROM pragma_table_info('" + table + "') WHERE name='deleted_at'", String.class))
                    .as(table + " 应有 deleted_at 列").containsExactly("deleted_at");
        }

        // deleted=0 却带着删除时间 -> 脏数据，必须被 CHECK 拦住
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> jdbc.update(
                "INSERT INTO task(title, created_at, deleted, deleted_at) VALUES (?, ?, 0, ?)",
                "没删却有删除时间", "2026-09-11 12:00:00", "2026-09-11 12:00:00"))
                .isInstanceOf(UncategorizedSQLException.class)
                .hasRootCauseInstanceOf(SQLiteException.class)
                .hasMessageContaining("CHECK constraint failed");
    }

    /** V6 的核心意图：日程日期放开为可空，NULL 即「待定时间」（US-4.2）。 */
    @Test
    void allowsNullEventDateForPendingScheduleItems() {
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);

        Integer eventDateNotNull = jdbc.queryForObject(
                "SELECT COUNT(*) FROM pragma_table_info('schedule_event') WHERE name='event_date' AND \"notnull\"=1",
                Integer.class);
        assertThat(eventDateNotNull).isZero();

        jdbc.update("INSERT INTO schedule_event(title, event_type, event_date, created_at, is_demo)"
                + " VALUES (?, ?, NULL, ?, 0)", "时间待定的日程", "meeting", "2026-09-11 12:00:00");
        Integer pending = jdbc.queryForObject(
                "SELECT COUNT(*) FROM schedule_event WHERE deleted=0 AND event_date IS NULL", Integer.class);
        assertThat(pending).isEqualTo(1);

        jdbc.update("DELETE FROM schedule_event WHERE title=?", "时间待定的日程");
    }
}
