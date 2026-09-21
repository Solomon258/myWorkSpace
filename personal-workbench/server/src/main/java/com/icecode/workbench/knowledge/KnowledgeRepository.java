package com.icecode.workbench.knowledge;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

@Repository
public class KnowledgeRepository {

    private final JdbcTemplate jdbcTemplate;

    private final RowMapper<KnowledgeNoteRecord> rowMapper = new RowMapper<KnowledgeNoteRecord>() {
        @Override
        public KnowledgeNoteRecord mapRow(ResultSet rs, int rowNum) throws SQLException {
            KnowledgeNoteRecord record = new KnowledgeNoteRecord();
            record.id = rs.getLong("id");
            record.title = rs.getString("title");
            record.content = rs.getString("content");
            record.tags = rs.getString("tags");
            record.vaultPath = rs.getString("vault_path");
            record.fileName = rs.getString("file_name");
            record.syncStatus = rs.getString("sync_status");
            long inboxId = rs.getLong("source_inbox_id");
            record.sourceInboxId = rs.wasNull() ? null : Long.valueOf(inboxId);
            record.createdAt = rs.getString("created_at");
            record.updatedAt = rs.getString("updated_at");
            return record;
        }
    };

    public KnowledgeRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public List<KnowledgeNoteRecord> findActive() {
        // 刻意不设隐式上限。这里原来写死 `LIMIT 200`，而界面上显示的是「（N 条）」——
        // 只有 N 就是全部时那句话才成立。超过 200 条之后，用户看到的是一个**假的总数**，
        // 而且更早的笔记再也翻不到（列表没有分页），属于静默丢数据。
        // 收藏与收集箱的列表同样不设上限：本地单用户、数据量小，让界面上的数字等于真相更重要。
        return jdbcTemplate.query("SELECT * FROM knowledge_note WHERE deleted=0 ORDER BY id DESC", rowMapper);
    }

    public KnowledgeNoteRecord findById(long id) {
        List<KnowledgeNoteRecord> list = jdbcTemplate.query(
                "SELECT * FROM knowledge_note WHERE id=? AND deleted=0", rowMapper, id);
        return list.isEmpty() ? null : list.get(0);
    }

    public long insert(String title, String content, String tags, String vaultPath, String fileName,
                       String syncStatus, Long sourceInboxId, String now) {
        // xerial 驱动对 null 参数的 setNull(TYPE_UNKNOWN) 会 NPE，统一显式类型 setter
        org.springframework.jdbc.support.GeneratedKeyHolder keyHolder =
                new org.springframework.jdbc.support.GeneratedKeyHolder();
        jdbcTemplate.update(connection -> {
            java.sql.PreparedStatement statement = connection.prepareStatement(
                    "INSERT INTO knowledge_note(title, content, tags, vault_path, file_name, sync_status, source_inbox_id, created_at, updated_at)"
                            + " VALUES (?,?,?,?,?,?,?,?,?)",
                    java.sql.Statement.RETURN_GENERATED_KEYS);
            statement.setString(1, title);
            statement.setString(2, content);
            statement.setString(3, tags);
            if (vaultPath == null) statement.setNull(4, java.sql.Types.VARCHAR); else statement.setString(4, vaultPath);
            if (fileName == null) statement.setNull(5, java.sql.Types.VARCHAR); else statement.setString(5, fileName);
            statement.setString(6, syncStatus);
            if (sourceInboxId == null) statement.setNull(7, java.sql.Types.BIGINT);
            else statement.setLong(7, sourceInboxId.longValue());
            statement.setString(8, now);
            statement.setString(9, now);
            return statement;
        }, keyHolder);
        return keyHolder.getKey().longValue();
    }

    public void updateSync(long id, String syncStatus, String vaultPath, String fileName, String now) {
        jdbcTemplate.update(connection -> {
            java.sql.PreparedStatement statement = connection.prepareStatement(
                    "UPDATE knowledge_note SET sync_status=?, vault_path=?, file_name=?, updated_at=? WHERE id=?");
            statement.setString(1, syncStatus);
            if (vaultPath == null) statement.setNull(2, java.sql.Types.VARCHAR); else statement.setString(2, vaultPath);
            if (fileName == null) statement.setNull(3, java.sql.Types.VARCHAR); else statement.setString(3, fileName);
            statement.setString(4, now);
            statement.setLong(5, id);
            return statement;
        });
    }

    /** 软删除必须同时写下 deleted_at：回收站靠它算 30 天窗口（V7）。 */
    public void softDelete(long id, String now) {
        // 补上 AND deleted=0：否则对已删记录再删一次会把 deleted_at 重置，白等一个 30 天窗口。
        jdbcTemplate.update("UPDATE knowledge_note SET deleted=1, deleted_at=?, updated_at=? WHERE id=? AND deleted=0", now, now, id);
    }

    /**
     * 该 Vault 文件是否已经有（未删除的）知识记录。
     * 存量导入靠它保证幂等 —— 用户多点几次「导入」不会长出重复条目。
     */
    public boolean existsByVaultPath(String vaultPath) {
        if (vaultPath == null) return false;
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM knowledge_note WHERE vault_path=? AND deleted=0", Integer.class, vaultPath);
        return count != null && count > 0;
    }

    public void insertActivity(String content, String now) {
        jdbcTemplate.update("INSERT INTO activity_log(log_type, category, content, created_at, is_demo) VALUES ('knowledge',NULL,?,?,0)",
                content, now);
    }
}
