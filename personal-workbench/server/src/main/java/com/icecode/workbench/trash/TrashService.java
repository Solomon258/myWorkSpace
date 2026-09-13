package com.icecode.workbench.trash;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.icecode.workbench.auth.AppConfigRepository;
import com.icecode.workbench.auth.AuthConstants;
import com.icecode.workbench.common.BizException;
import com.icecode.workbench.common.ErrorCode;
import com.icecode.workbench.util.TextUtil;
import com.icecode.workbench.util.TimeUtil;

/**
 * 回收站与恢复（US-1.5「软删除，30 天内可恢复」）。
 *
 * <p>在这之前 `deleted=1` 只是个过滤位：没有恢复接口、界面上也没有入口，
 * 「可恢复」成了一句无处可点的承诺。本服务把「删除时间」变成可查的事实（V7 加的
 * {@code deleted_at}），并提供按类型恢复。</p>
 *
 * <p>删除时间的取法：{@code COALESCE(deleted_at, updated_at, created_at)}。
 * 兜底是给 V7 之前就删掉的历史记录用的——那时没有 deleted_at，
 * 而旧实现的软删除会同时更新 updated_at，所以拿它当删除时间是准确的。</p>
 */
@Service
public class TrashService {

    /** 保留期。与 US-1.5 / 产品设计说明 §5.2 的「30 天内可恢复」对齐。 */
    static final int RETENTION_DAYS = 30;

    /** 删除时间的统一表达式，列表过滤与窗口判断都用它，避免两处写出不同口径。 */
    private static final String DELETED_AT = "COALESCE(deleted_at, updated_at, created_at)";

    /**
     * 每种实体的读取方式集中在一处。
     * 「标题取哪一列」既要给列表用、又要给恢复后的 toast 用，
     * 分散成两份实现最容易出现「列表显示的是 A、恢复时说成 B」这类错位。
     */
    private static final Map<String, EntitySpec> ENTITIES = new LinkedHashMap<String, EntitySpec>();

    static {
        ENTITIES.put("inbox", new EntitySpec("inbox_item", "id, raw_content, source, status") {
            @Override
            String title(ResultSet rs) throws SQLException {
                return TextUtil.clip(rs.getString("raw_content"), 60);
            }

            @Override
            String detail(ResultSet rs) throws SQLException {
                return "来源 " + sourceName(rs.getString("source")) + " · " + inboxStatusName(rs.getString("status"));
            }
        });
        ENTITIES.put("task", new EntitySpec("task", "id, title, priority, status, due_date") {
            @Override
            String title(ResultSet rs) throws SQLException {
                return rs.getString("title");
            }

            @Override
            String detail(ResultSet rs) throws SQLException {
                String due = rs.getString("due_date");
                return rs.getString("priority") + " · " + taskStatusName(rs.getString("status"))
                        + (due == null || due.isEmpty() ? "" : " · 截止 " + due);
            }
        });
        ENTITIES.put("event", new EntitySpec("schedule_event", "id, title, event_type, event_date, start_time") {
            @Override
            String title(ResultSet rs) throws SQLException {
                return rs.getString("title");
            }

            @Override
            String detail(ResultSet rs) throws SQLException {
                String date = rs.getString("event_date");
                String start = rs.getString("start_time");
                String when = date == null ? "时间待定" : (start == null ? date : date + " " + start);
                return when + " · " + eventTypeName(rs.getString("event_type"));
            }
        });
        ENTITIES.put("memo", new EntitySpec("memo", "id, title, content, grp") {
            @Override
            String title(ResultSet rs) throws SQLException {
                // 备忘常常只有正文没有标题。此时用正文当标题，
                // 否则回收站里会出现一片认不出是哪条的空白条目。
                String title = rs.getString("title");
                if (title != null && !title.trim().isEmpty()) return title;
                return TextUtil.clip(rs.getString("content"), 60);
            }

            @Override
            String detail(ResultSet rs) throws SQLException {
                return "life".equals(rs.getString("grp")) ? "生活" : "工作";
            }
        });
        ENTITIES.put("knowledge", new EntitySpec("knowledge_note", "id, title, file_name, vault_path") {
            @Override
            String title(ResultSet rs) throws SQLException {
                return rs.getString("title");
            }

            @Override
            String detail(ResultSet rs) throws SQLException {
                String file = rs.getString("file_name");
                if (file != null && !file.trim().isEmpty()) return "Vault 文件 " + file;
                String path = rs.getString("vault_path");
                return path == null ? "Vault 里的 .md 仍保留在原处" : path;
            }
        });
    }

    private final JdbcTemplate jdbcTemplate;
    private final AppConfigRepository configRepository;
    private final RowMapper<TrashItemVO> listMapper = new RowMapper<TrashItemVO>() {
        @Override
        public TrashItemVO mapRow(ResultSet rs, int rowNum) throws SQLException {
            String type = rs.getString("trash_type");
            EntitySpec spec = ENTITIES.get(type);
            String deletedAt = rs.getString("deleted_at");
            return new TrashItemVO(type, rs.getLong("id"), spec.title(rs), spec.detail(rs), deletedAt, daysLeft(deletedAt));
        }
    };

    public TrashService(JdbcTemplate jdbcTemplate, AppConfigRepository configRepository) {
        this.jdbcTemplate = jdbcTemplate;
        this.configRepository = configRepository;
    }

    /** 回收站列表：5 类实体统一成一条按删除时间倒序的流，只保留仍在保留期内的。 */
    public List<TrashItemVO> list() {
        String cutoff = TimeUtil.daysAgo(timezone(), RETENTION_DAYS);
        List<TrashItemVO> result = new ArrayList<TrashItemVO>();
        for (Map.Entry<String, EntitySpec> entry : ENTITIES.entrySet()) {
            EntitySpec spec = entry.getValue();
            // trash_type 是给共用的 rowMapper 认表的：5 类实体列不同，只能让 SQL 自己带上类型。
            String sql = "SELECT '" + entry.getKey() + "' AS trash_type, " + spec.columns
                    + ", " + DELETED_AT + " AS deleted_at FROM " + spec.table
                    + " WHERE deleted=1 AND " + DELETED_AT + " >= ? ORDER BY deleted_at DESC, id DESC";
            result.addAll(jdbcTemplate.query(sql, new Object[] {cutoff}, listMapper));
        }
        // 跨表合并后再整体排序：每张表内的 ORDER BY 只能保证表内有序。
        Collections.sort(result, new Comparator<TrashItemVO>() {
            @Override
            public int compare(TrashItemVO left, TrashItemVO right) {
                int byTime = String.valueOf(right.getDeletedAt()).compareTo(String.valueOf(left.getDeletedAt()));
                return byTime != 0 ? byTime : Long.compare(right.getId(), left.getId());
            }
        });
        return result;
    }

    /** 回收站角标用的数量。 */
    public int count() {
        return list().size();
    }

    @Transactional(rollbackFor = Exception.class)
    public TrashRestoreVO restore(String type, long id) {
        EntitySpec spec = ENTITIES.get(type);
        if (spec == null) {
            // 类型写错时把合法取值列出来，否则调用方只能靠猜
            throw new BizException(ErrorCode.INVALID_PARAMETER,
                    "不支持的回收站类型「" + type + "」，只支持：" + String.join(" / ", ENTITIES.keySet()));
        }
        String now = TimeUtil.now(timezone());
        String cutoff = TimeUtil.daysAgo(timezone(), RETENTION_DAYS);

        // 先查再改：0 行更新有三种完全不同的原因（不存在 / 没被删 / 过了保留期）。
        // 只报「恢复失败」等于什么都没说，用户不知道该做什么。
        List<TrashItemVO> rows = jdbcTemplate.query(
                "SELECT '" + type + "' AS trash_type, " + spec.columns + ", " + DELETED_AT + " AS deleted_at"
                        + " FROM " + spec.table + " WHERE id=?", new Object[] {id}, listMapper);
        TrashItemVO item = rows.isEmpty() ? null : rows.get(0);
        if (item == null) {
            throw new BizException(ErrorCode.RESOURCE_NOT_FOUND,
                    "回收站里没有这条记录（类型 " + type + "，id " + id + "），可能已被恢复或已被彻底清除");
        }
        if (isActive(spec, id)) {
            throw new BizException(ErrorCode.INVALID_PARAMETER, "「" + item.getTitle() + "」没有被删除，不需要恢复");
        }
        if (expired(item.getDeletedAt(), cutoff)) {
            throw new BizException(ErrorCode.INVALID_PARAMETER,
                    "「" + item.getTitle() + "」已于 " + item.getDeletedAt() + " 删除，超过 " + RETENTION_DAYS
                            + " 天保留期，不能再恢复");
        }

        int affected = jdbcTemplate.update("UPDATE " + spec.table
                + " SET deleted=0, deleted_at=NULL, updated_at=? WHERE id=? AND deleted=1", now, id);
        if (affected == 0) {
            // 并发下被别人抢先恢复或彻底清除了
            throw new BizException(ErrorCode.RESOURCE_NOT_FOUND, "这条记录刚刚已被恢复或清除，请刷新回收站");
        }
        jdbcTemplate.update("INSERT INTO activity_log(log_type, content, created_at, is_demo) VALUES ('task',?,?,0)",
                "从回收站恢复「" + item.getTitle() + "」", now);
        return new TrashRestoreVO(type, id, item.getTitle());
    }

    /**
     * 条目当前是否处于「未删除」状态。
     * 单独查一次 deleted，是为了不把 deleted 提升成 TrashItemVO 的字段——
     * 那个 VO 是给前端列表看的，多带一个内部标记只会让前端以为它有语义。
     */
    private boolean isActive(EntitySpec spec, long id) {
        Integer deleted = jdbcTemplate.queryForObject(
                "SELECT deleted FROM " + spec.table + " WHERE id=?", Integer.class, id);
        return deleted == null || deleted.intValue() != 1;
    }

    private boolean expired(String deletedAt, String cutoff) {
        return deletedAt != null && deletedAt.compareTo(cutoff) < 0;
    }

    private int daysLeft(String deletedAt) {
        LocalDateTime deleted = TimeUtil.parseDateTime(deletedAt);
        if (deleted == null) return RETENTION_DAYS;
        LocalDateTime now = LocalDateTime.now(TimeUtil.zone(timezone()));
        long elapsed = ChronoUnit.DAYS.between(deleted, now);
        int left = RETENTION_DAYS - (int) elapsed;
        return left < 0 ? 0 : left;
    }

    private String timezone() {
        String timezone = configRepository.findValue(AuthConstants.CONFIG_TIMEZONE);
        return timezone == null ? "Asia/Shanghai" : timezone;
    }

    /**
     * 下面几个是把库里的英文枚举翻成人话。
     *
     * <p>回收站是**用户唯一会看到「已删数据原始字段」的地方**，而这些字段在库里都是英文标记
     * （{@code processed} / {@code doing} / {@code deep_block}…）。若直接拼进摘要，
     * 用户看到的就是「来源 web · 已归档」这种半中半英的东西，认不出这条到底是什么，
     * 也就不敢点恢复。这里刻意不复用前端的 name 映射——后端的摘要是给「没有前端」的
     * 场景（curl、排障）也要能读懂的。</p>
     */
    private static String sourceName(String source) {
        return "wecom".equals(source) ? "微信" : "Web";
    }

    private static String inboxStatusName(String status) {
        if ("pending".equals(status)) return "待整理";
        if ("processed".equals(status)) return "待确认";
        if ("failed".equals(status)) return "整理失败";
        if ("archived".equals(status)) return "已归档";
        return status;
    }

    private static String taskStatusName(String status) {
        if ("todo".equals(status)) return "待办";
        if ("doing".equals(status)) return "进行中";
        if ("done".equals(status)) return "已完成";
        if ("canceled".equals(status)) return "已取消";
        return status;
    }

    private static String eventTypeName(String type) {
        if ("meeting".equals(type)) return "会议";
        if ("deep_block".equals(type)) return "深度块";
        if ("other".equals(type)) return "其他";
        return type;
    }

    /** 一种实体的读取方式：目标表 + 要 SELECT 的列 + 标题/摘要的取值方式。 */
    private abstract static class EntitySpec {
        final String table;
        final String columns;

        EntitySpec(String table, String columns) {
            this.table = table;
            this.columns = columns;
        }

        abstract String title(ResultSet rs) throws SQLException;

        abstract String detail(ResultSet rs) throws SQLException;
    }
}
