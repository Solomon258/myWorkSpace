package com.icecode.workbench.search;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Service;

import com.icecode.workbench.auth.AppConfigRepository;
import com.icecode.workbench.auth.AuthConstants;
import com.icecode.workbench.common.BizException;
import com.icecode.workbench.common.ErrorCode;
import com.icecode.workbench.util.TextUtil;
import com.icecode.workbench.util.TimeUtil;

/**
 * 全局搜索：跨 收录 / 任务 / 日程 / 备忘 / 时间线 五类实体 + 回收站。
 *
 * <p>设计见 {@code docs/全文搜索功能设计.md}。四件必须守住的事：</p>
 * <ol>
 *   <li><b>不引入 FTS5</b>。单机单用户、数据千级，FTS5 要付出「迁移 + 中文分词器（unicode61
 *       对中文无效）+ 索引同步」三重代价。这里用 LIKE 粗筛 + 应用层打分：
 *       SQL 只负责「有没有命中」（能走索引、结果集小），
 *       「命中在标题还是正文、该排前面还是后面」在 Java 侧算 —— SQL 表达不了这个。</li>
 *   <li><b>回收站独立成组</b>，且组内条目的落点是回收站页。任务/日程/备忘页查的都是
 *       {@code deleted=0}，把软删数据的落点写成原实体页，用户点开就是「点了没反应」。</li>
 *   <li><b>不跨类型混排</b>。按 任务 → 日程 → 备忘 → 收录 → 时间线 → 回收站 固定顺序分组，
 *       组内才排序。混排会带来「为什么这条排前面」的困惑，收益却很小。</li>
 *   <li><b>截断必须说出来</b>。每组返回 {@code total} 与 {@code hasMore}，前端显示
 *       「该分组还有 N 条未显示」——静默截断是本项目已经发生过的事故类型。</li>
 * </ol>
 */
@Service
public class SearchService {

    /** 关键词长度上限。前端输入框的 maxlength 必须与这里一致（写小了会静默截断用户输入）。 */
    static final int MAX_QUERY = 100;

    static final int DEFAULT_LIMIT = 5;
    static final int MAX_LIMIT = 20;

    /** 摘要片段的上下文宽度：命中位置往前 20 字、往后 60 字。 */
    private static final int SNIPPET_BEFORE = 20;
    private static final int SNIPPET_AFTER = 60;

    private static final int MAX_MATCHED_TERMS = 8;

    /** 语义扩展词最多采纳几个。模型偶尔会一口气吐十几条，那会把结果页冲垮。 */
    private static final int MAX_EXPAND_TERMS = 5;

    /** 字段层级。数字越小越「重要」，打分与「命中在哪」的展示都按它排序。 */
    private static final int TIER_TITLE = 1;
    private static final int TIER_TAGS = 2;
    private static final int TIER_BODY = 3;
    private static final int TIER_RAW = 4;
    private static final int TIER_LOG = 5;

    /** 非标题层级的封顶分。标题另算（整串 100 / 分词 60），见 {@link #match}。 */
    private static final Map<Integer, Integer> TIER_SCORE = new LinkedHashMap<Integer, Integer>();
    static {
        TIER_SCORE.put(Integer.valueOf(TIER_TAGS), Integer.valueOf(40));
        TIER_SCORE.put(Integer.valueOf(TIER_BODY), Integer.valueOf(25));
        TIER_SCORE.put(Integer.valueOf(TIER_RAW), Integer.valueOf(20));
        TIER_SCORE.put(Integer.valueOf(TIER_LOG), Integer.valueOf(15));
    }

    /** 7 天内更新过的新近度加分。 */
    private static final int RECENT_BONUS = 8;
    /** 已删除的降权：只降序、不隐藏（用户选了「含回收站」，但活数据必须排在前面）。 */
    private static final int DELETED_PENALTY = 30;
    /** 已归档备忘 / 已取消任务的降权。 */
    private static final int DEMOTED_PENALTY = 10;

    private static final String TYPE_TRASH = "trash";

    /** 分组顺序就是这里的插入顺序，禁止改成按命中数排序（见类注释第 3 条）。 */
    private static final List<EntitySpec> ENTITIES = new ArrayList<EntitySpec>();
    private static final Set<String> ALL_TYPES = new LinkedHashSet<String>();

    static {
        ENTITIES.add(new EntitySpec("task", "任务", "task",
                "id, title, note, description, priority, status, due_date, is_deep_work, is_blocking, updated_at",
                "title", "updated_at", true) {
            @Override
            String title(ResultSet rs) throws SQLException {
                return rs.getString("title");
            }

            @Override
            Map<String, Object> meta(ResultSet rs) throws SQLException {
                Map<String, Object> meta = new LinkedHashMap<String, Object>();
                meta.put("priority", rs.getString("priority"));
                meta.put("status", rs.getString("status"));
                meta.put("due", rs.getString("due_date"));
                meta.put("deep", Boolean.valueOf(rs.getInt("is_deep_work") == 1));
                meta.put("blocking", Boolean.valueOf(rs.getInt("is_blocking") == 1));
                return meta;
            }

            @Override
            SearchTargetVO target(ResultSet rs) throws SQLException {
                // 任务页没有「按日」的概念，落点就是任务页；前端落点前会先清筛选，
                // 否则这条可能正被上一次的筛选挡着，高亮目标不在 DOM 里。
                return SearchTargetVO.tab("tasks");
            }

            @Override
            boolean demoted(ResultSet rs) throws SQLException {
                return "canceled".equals(rs.getString("status"));
            }
        });
        ENTITIES.add(new EntitySpec("event", "日程", "schedule_event",
                "id, title, event_type, event_date, start_time, end_time, updated_at",
                "title", "updated_at", true) {
            @Override
            String title(ResultSet rs) throws SQLException {
                return rs.getString("title");
            }

            @Override
            Map<String, Object> meta(ResultSet rs) throws SQLException {
                Map<String, Object> meta = new LinkedHashMap<String, Object>();
                meta.put("type", rs.getString("event_type"));
                meta.put("date", rs.getString("event_date"));
                meta.put("start", rs.getString("start_time"));
                meta.put("end", rs.getString("end_time"));
                return meta;
            }

            @Override
            SearchTargetVO target(ResultSet rs) throws SQLException {
                // event_date 为 null = 待定时间（US-4.2）。这个 null 必须原样传给前端，
                // 让前端滚到「待定时间」区；任何方向都不要在这里补今天。
                return SearchTargetVO.event(rs.getString("event_date"));
            }

            @Override
            boolean demoted(ResultSet rs) throws SQLException {
                return false;
            }
        });
        ENTITIES.add(new EntitySpec("memo", "备忘", "memo",
                "id, title, content, url, tags, grp, status, updated_at",
                "title", "updated_at", true) {
            @Override
            String title(ResultSet rs) throws SQLException {
                // 备忘允许「有正文没标题」，此时用正文前 60 字当标题。
                // 否则结果行会是一片认不出是哪条的空白条目。
                String title = rs.getString("title");
                if (title != null && !title.trim().isEmpty()) return title;
                return TextUtil.clip(rs.getString("content"), 60);
            }

            @Override
            Map<String, Object> meta(ResultSet rs) throws SQLException {
                Map<String, Object> meta = new LinkedHashMap<String, Object>();
                meta.put("grp", rs.getString("grp"));
                meta.put("tags", rs.getString("tags"));
                meta.put("archived", Boolean.valueOf("archived".equals(rs.getString("status"))));
                return meta;
            }

            @Override
            SearchTargetVO target(ResultSet rs) throws SQLException {
                // grp 决定前端先切到哪个空间。空间不对 → 目标行不在 DOM 里 → 「点了没反应」。
                return SearchTargetVO.memo(rs.getString("grp"));
            }

            @Override
            boolean demoted(ResultSet rs) throws SQLException {
                return "archived".equals(rs.getString("status"));
            }
        });
        ENTITIES.add(new EntitySpec("inbox", "收录", "inbox_item",
                "id, raw_content, source, status, created_at, updated_at",
                "raw_content", "updated_at", true) {
            @Override
            String title(ResultSet rs) throws SQLException {
                return TextUtil.clip(rs.getString("raw_content"), 60);
            }

            @Override
            Map<String, Object> meta(ResultSet rs) throws SQLException {
                Map<String, Object> meta = new LinkedHashMap<String, Object>();
                meta.put("status", rs.getString("status"));
                meta.put("source", rs.getString("source"));
                meta.put("createdAt", rs.getString("created_at"));
                return meta;
            }

            @Override
            SearchTargetVO target(ResultSet rs) throws SQLException {
                return SearchTargetVO.tab("inbox");
            }

            @Override
            boolean demoted(ResultSet rs) throws SQLException {
                return false;
            }
        });
        ENTITIES.add(new EntitySpec("timeline", "时间线", "activity_log",
                "id, log_type, content, created_at",
                "content", "created_at", false) {
            @Override
            String title(ResultSet rs) throws SQLException {
                return TextUtil.clip(rs.getString("content"), 60);
            }

            @Override
            Map<String, Object> meta(ResultSet rs) throws SQLException {
                Map<String, Object> meta = new LinkedHashMap<String, Object>();
                meta.put("logType", rs.getString("log_type"));
                meta.put("createdAt", rs.getString("created_at"));
                return meta;
            }

            @Override
            SearchTargetVO target(ResultSet rs) throws SQLException {
                // 时间线按日分组并且可以折叠。前端落点前必须先展开那一天，
                // 否则高亮目标在 display:none 的容器里。
                return SearchTargetVO.timeline(day(rs.getString("created_at")));
            }

            @Override
            boolean demoted(ResultSet rs) throws SQLException {
                return false;
            }
        });

        // 检索字段与层级：标题 > 标签 > 正文 > 收录原文 > 时间线内容。
        // 顺序不重要（打分只看 tier），但每个实体至少要有一个 tier=1 的字段。
        ENTITIES.get(0).fields = Arrays.asList(
                new Field("title", TIER_TITLE, SearchItemVO.FIELD_TITLE),
                new Field("note", TIER_BODY, SearchItemVO.FIELD_BODY),
                new Field("description", TIER_BODY, SearchItemVO.FIELD_BODY));
        ENTITIES.get(1).fields = Arrays.asList(
                new Field("title", TIER_TITLE, SearchItemVO.FIELD_TITLE));
        ENTITIES.get(2).fields = Arrays.asList(
                new Field("title", TIER_TITLE, SearchItemVO.FIELD_TITLE),
                new Field("tags", TIER_TAGS, SearchItemVO.FIELD_TAGS),
                new Field("content", TIER_BODY, SearchItemVO.FIELD_BODY),
                new Field("url", TIER_BODY, SearchItemVO.FIELD_BODY));
        ENTITIES.get(3).fields = Arrays.asList(
                new Field("raw_content", TIER_RAW, SearchItemVO.FIELD_RAW));
        ENTITIES.get(4).fields = Arrays.asList(
                new Field("content", TIER_LOG, SearchItemVO.FIELD_LOG));

        // 「收录」这一类的范围由用户 2026-09-12 确认：待整理 / 待确认 / 整理失败，
        // **不含已归档**。failed 必须留着 —— 它的 status 既不是 pending 也不是 processed，
        // 漏掉的话「整理失败但还没重试」的条目在搜索里彻底找不到，
        // 而收录页的「待整理」区正是靠 pending + failed 才把它露出来的。
        ENTITIES.get(3).extraWhere = "status IN ('pending','processed','failed')";

        for (EntitySpec spec : ENTITIES) ALL_TYPES.add(spec.type);
        // 回收站是「一个分组」而不是「一种实体」：它的条目来自上面 5 张表，
        // 但界面把它们放在最后一组、灰化、标「在回收站」。
        ALL_TYPES.add(TYPE_TRASH);
    }

    private final JdbcTemplate jdbcTemplate;
    private final AppConfigRepository configRepository;

    public SearchService(JdbcTemplate jdbcTemplate, AppConfigRepository configRepository) {
        this.jdbcTemplate = jdbcTemplate;
        this.configRepository = configRepository;
    }

    public SearchResultVO search(String q, String types, String scope, int limit, String expand) {
        long started = System.currentTimeMillis();

        String query = q == null ? "" : q.trim();
        if (query.isEmpty()) {
            throw new BizException(ErrorCode.INVALID_PARAMETER, "请输入要搜索的关键词");
        }
        if (query.length() > MAX_QUERY) {
            throw new BizException(ErrorCode.INVALID_PARAMETER,
                    "关键词不能超过 " + MAX_QUERY + " 字，当前 " + query.length() + " 字，请缩短后重试");
        }
        List<String> terms = SearchTerms.split(query);
        if (terms.isEmpty()) {
            // 只输入标点/空白时切不出检索词。这里必须显式拦下：
            // 放过去的话 SQL 的 WHERE 会退化成没有条件，一次搜索返回整库数据。
            throw new BizException(ErrorCode.INVALID_PARAMETER, "关键词里没有可检索的文字，请换一个词试试");
        }

        int size = normalizeLimit(limit);
        String normalizedScope = normalizeScope(scope);
        Set<String> wanted = parseTypes(types);
        String rawLower = query.toLowerCase();
        List<String> expandTerms = parseExpandTerms(expand, terms);

        List<SearchGroupVO> groups = new ArrayList<SearchGroupVO>();
        int totalCount = 0;
        boolean truncated = false;

        for (EntitySpec spec : ENTITIES) {
            if (!wanted.contains(spec.type)) continue;
            SearchGroupVO group = buildGroup(spec, terms, expandTerms, rawLower, size, false);
            // 没有命中的分组不返回：前端是「有命中才画分组头 + chip」，
            // 返回一堆空组会让界面出现「任务 0 / 日程 0 / …」的噪音。
            if (group.getTotal() == 0) continue;
            groups.add(group);
            totalCount += group.getTotal();
            if (group.isHasMore()) truncated = true;
        }

        // scope=active 时整个回收站分组都不出现。这里不与 types 里的 trash 冲突：
        // 用户要「只看活数据」时，即使 chips 里还点着回收站，也应该以 scope 为准。
        if ("all".equals(normalizedScope) && wanted.contains(TYPE_TRASH)) {
            SearchGroupVO trash = buildTrashGroup(terms, expandTerms, rawLower, size);
            if (trash.getTotal() > 0) {
                groups.add(trash);
                totalCount += trash.getTotal();
                if (trash.isHasMore()) truncated = true;
            }
        }

        return new SearchResultVO(query, expandTerms,
                expandTerms.isEmpty() ? SearchResultVO.SEMANTIC_OFF : SearchResultVO.SEMANTIC_READY,
                System.currentTimeMillis() - started, truncated, totalCount, groups);
    }

    /** 单个实体分组（活数据）。 */
    private SearchGroupVO buildGroup(EntitySpec spec, List<String> terms, List<String> expandTerms,
                                     String rawLower, int limit, boolean deleted) {
        return toGroup(spec.type, spec.label, withExpanded(spec, terms, expandTerms, rawLower, deleted), limit);
    }

    /**
     * 主检索 + 语义扩展检索。
     *
     * <p>两次查询分开跑、结果再合并，而不是把两组词塞进一个 SQL：命中来源必须能分辨
     * （「精确命中」和「联想命中」在结果行上是两句不同的话），分开跑才谈得上
     * 「同一条记录只出现一次，且保留分数更高的那个来源」。</p>
     */
    private List<Row> withExpanded(EntitySpec spec, List<String> terms, List<String> expandTerms,
                                   String rawLower, boolean deleted) {
        List<Row> rows = new ArrayList<Row>(query(spec, terms, rawLower, deleted, false));
        if (expandTerms.isEmpty()) return rows;
        Set<String> seen = new HashSet<String>();
        for (Row row : rows) seen.add(row.entity + "#" + row.id);
        // 主命中优先：扩展词命中的同一条记录不再重复出现（不覆盖已知的更高分来源）
        for (Row row : query(spec, expandTerms, rawLower, deleted, true)) {
            if (seen.add(row.entity + "#" + row.id)) rows.add(row);
        }
        return rows;
    }

    /**
     * 回收站分组：把 5 张表的已删记录合成一组再排序。
     *
     * <p>为什么不合进各自的类型分组：一条被软删的任务如果还留在「任务 · 2」里，
     * 用户会以为任务页坏了（那页面上确实看不到它）。独立成组 + 沉到最后 + 灰化，
     * 既找得到、又不污染活数据的分组。</p>
     */
    private SearchGroupVO buildTrashGroup(List<String> terms, List<String> expandTerms, String rawLower, int limit) {
        List<Row> rows = new ArrayList<Row>();
        for (EntitySpec spec : ENTITIES) {
            if (!spec.softDelete) continue; // 时间线是物理删除，不存在「已删记录」
            rows.addAll(withExpanded(spec, terms, expandTerms, rawLower, true));
        }
        return toGroup(TYPE_TRASH, "回收站", rows, limit);
    }

    private SearchGroupVO toGroup(String type, String label, List<Row> rows, int limit) {
        sort(rows);
        int total = rows.size();
        List<SearchItemVO> items = new ArrayList<SearchItemVO>();
        for (int i = 0; i < rows.size() && i < limit; i++) items.add(rows.get(i).item);
        return new SearchGroupVO(type, label, total, total > items.size(), items);
    }

    /**
     * 排序必须在 Java 侧整体做，不能交给 SQL：
     * 分数的构成（标题命中 / 标签命中 / 正文命中 / 新近度 / 降权）SQL 表达不了，
     * 而 LIMIT 只能加在最终顺序上，否则截断掉的可能是本该排第一的那条。
     */
    private void sort(List<Row> rows) {
        Collections.sort(rows, new Comparator<Row>() {
            @Override
            public int compare(Row left, Row right) {
                int byScore = Integer.compare(right.score, left.score);
                if (byScore != 0) return byScore;
                int byTime = String.valueOf(right.updatedAt).compareTo(String.valueOf(left.updatedAt));
                if (byTime != 0) return byTime;
                // 跨表合并时 id 会撞（task#5 与 memo#5），所以最后必须用 entity 定序，
                // 否则同一查询两次执行可能出现不同顺序，测试无法断言。
                int byEntity = left.entity.compareTo(right.entity);
                if (byEntity != 0) return byEntity;
                return Long.compare(right.id, left.id);
            }
        });
    }

    private List<Row> query(EntitySpec spec, List<String> terms, String rawLower, final boolean deleted,
                            final boolean semanticOnly) {
        StringBuilder sql = new StringBuilder("SELECT ").append(spec.selectColumns)
                .append(" FROM ").append(spec.table);
        List<Object> args = new ArrayList<Object>();
        // 条件拼接必须显式记住「WHERE 有没有开过头」。时间线（activity_log）没有 deleted 列，
        // 如果只按 `if (softDelete) 加 WHERE`、后面一律用 AND 接，
        // 这张表拼出来就是 `FROM activity_log AND (...)` —— 语法错误。
        // 另外 4 张表都有 deleted 列，会把这个缺陷完整盖住，只在搜到时间线时才炸。
        boolean whereStarted = false;
        if (spec.softDelete) {
            sql.append(" WHERE deleted=").append(deleted ? "1" : "0");
            whereStarted = true;
        }
        // 实体自己的业务范围条件（目前只有「收录」用到）。只在查活数据时生效：
        // 回收站里的条目不论原来是什么状态都属于「已删数据」，用户要的是「全部含回收站」，
        // 再套一层 status 过滤会让回收站里那批已归档的收录搜不到。
        if (!deleted && spec.extraWhere != null) {
            sql.append(whereStarted ? " AND " : " WHERE ").append(spec.extraWhere);
            whereStarted = true;
        }
        for (String term : terms) {
            // 每个检索词都必须至少命中一个字段（词之间是 AND，字段之间是 OR）。
            // 词之间若用 OR，搜「限流 配额」会把只含「配额」的几十条全捞出来，
            // 排序也救不回来——用户会觉得「搜两个词反而更不准」。
            sql.append(whereStarted ? " AND (" : " WHERE (");
            whereStarted = true;
            for (int i = 0; i < spec.fields.size(); i++) {
                if (i > 0) sql.append(" OR ");
                sql.append("LOWER(COALESCE(").append(spec.fields.get(i).column)
                        .append(",'')) LIKE ? ESCAPE '\\'");
                args.add(SearchTerms.likePattern(term));
            }
            sql.append(")");
        }
        sql.append(" ORDER BY ").append(spec.orderColumn).append(" DESC, id DESC");

        return jdbcTemplate.query(sql.toString(), args.toArray(), new RowMapper<Row>() {
            @Override
            public Row mapRow(ResultSet rs, int rowNum) throws SQLException {
                long id = rs.getLong("id");
                String updatedAt = rs.getString(spec.orderColumn);
                Match match = match(spec, rs, terms, rawLower);
                SearchItemVO item = new SearchItemVO(spec.type, id, spec.title(rs),
                        snippet(spec, rs, terms, rawLower, match.column), match.terms,
                        semanticOnly ? SearchItemVO.KIND_SEMANTIC : match.kind, match.field,
                        deleted, spec.meta(rs), updatedAt,
                        deleted ? SearchTargetVO.tab(TYPE_TRASH) : spec.target(rs));
                // 语义命中只算关键字命中一半的分量：它是「像」而不是「是」。
                // 同分的话，结果页第一条经常会变成一条用户根本没搜过的词，
                // 「为什么这条排第一」的困惑就是这么来的。
                int score = semanticOnly ? match.score / 2 : match.score;
                score += deleted ? -DELETED_PENALTY : recency(updatedAt);
                if (spec.demoted(rs)) score -= DEMOTED_PENALTY;
                return new Row(spec.type, id, updatedAt, score, item);
            }
        });
    }

    /**
     * 判断一条记录怎么匹配上的：命中在哪个字段、算整串命中还是分词命中、得多少分。
     *
     * <p>分数用「层级封顶」而不是「逐词累加」：累加会让长标题、长正文靠词多刷分，
     * 结果变得不可预测（同一段文字加两个字就换位置）。封顶之后
     * 「标题 > 标签 > 正文 > 原文 > 日志」这条主线始终成立。</p>
     */
    private Match match(EntitySpec spec, ResultSet rs, List<String> terms, String rawLower) throws SQLException {
        Match match = new Match();
        int bestTier = Integer.MAX_VALUE;
        boolean rawHitAnywhere = false;
        boolean rawHitInTitle = false;

        for (Field field : spec.fields) {
            String value = rs.getString(field.column);
            if (value == null || value.isEmpty()) continue;
            String lower = value.toLowerCase();
            boolean hit = false;

            if (!rawLower.isEmpty() && lower.contains(rawLower)) {
                rawHitAnywhere = true;
                hit = true;
                if (field.tier == TIER_TITLE) rawHitInTitle = true;
                addTerm(match.terms, rawLower);
            }
            for (String term : terms) {
                if (lower.contains(term)) {
                    hit = true;
                    addTerm(match.terms, term);
                }
            }

            if (hit && field.tier < bestTier) {
                bestTier = field.tier;
                match.field = field.name;
                match.column = field.column;
            }
        }

        match.kind = rawHitAnywhere ? SearchItemVO.KIND_EXACT : SearchItemVO.KIND_SEMI;
        if (bestTier == TIER_TITLE) {
            match.score = rawHitInTitle ? 100 : 60;
        } else {
            Integer tierScore = TIER_SCORE.get(Integer.valueOf(bestTier));
            match.score = tierScore == null ? 0 : tierScore.intValue();
        }

        // 高亮词按长度降序：前端按这个顺序替换，长词优先，短词不会把长词切碎。
        Collections.sort(match.terms, new Comparator<String>() {
            @Override
            public int compare(String left, String right) {
                return Integer.compare(right.length(), left.length());
            }
        });
        if (match.terms.size() > MAX_MATCHED_TERMS) {
            match.terms = new ArrayList<String>(match.terms.subList(0, MAX_MATCHED_TERMS));
        }
        return match;
    }

    private static void addTerm(List<String> terms, String term) {
        if (term != null && !term.isEmpty() && !terms.contains(term)) terms.add(term);
    }

    /**
     * 生成摘要片段。命中在标题时改用正文做上下文 ——
     * 标题本身已经在上方显示了，再拿标题当片段等于那一行什么都没多说。
     */
    private String snippet(EntitySpec spec, ResultSet rs, List<String> terms, String rawLower, String hitColumn)
            throws SQLException {
        boolean titleOnly = spec.fields.size() == 1 && spec.fields.get(0).column.equals(spec.titleColumn);
        String source;
        String needle;
        if (hitColumn != null && (titleOnly || !hitColumn.equals(spec.titleColumn))) {
            source = rs.getString(hitColumn);
            needle = rawLower;
        } else {
            source = null;
            for (Field field : spec.fields) {
                if (field.tier == TIER_TITLE) continue;
                String value = rs.getString(field.column);
                if (value != null && !value.trim().isEmpty()) {
                    source = value;
                    break;
                }
            }
            needle = "";
        }
        if (source == null || source.trim().isEmpty()) return "";

        String flat = source.replaceAll("\\s+", " ").trim();
        String lower = flat.toLowerCase();
        int position = -1;
        if (needle != null && !needle.isEmpty()) position = lower.indexOf(needle);
        if (position < 0) {
            for (String term : terms) {
                int found = lower.indexOf(term);
                if (found >= 0 && (position < 0 || found < position)) position = found;
            }
        }
        if (position < 0) return TextUtil.clip(flat, SNIPPET_BEFORE + SNIPPET_AFTER);

        int start = Math.max(0, position - SNIPPET_BEFORE);
        int end = Math.min(flat.length(), position + SNIPPET_AFTER);
        // 这是纯展示片段（不上库），可以拼省略号；落库文本的裁剪一律用 TextUtil.clip。
        return (start > 0 ? "…" : "") + flat.substring(start, end).trim() + (end < flat.length() ? "…" : "");
    }

    /** 7 天内更新 +8 分（次要因子，只在同层级之间起作用）。 */
    private int recency(String updatedAt) {
        LocalDateTime time = TimeUtil.parseDateTime(updatedAt);
        if (time == null) return 0;
        LocalDateTime now = LocalDateTime.now(TimeUtil.zone(timezone()));
        return ChronoUnit.DAYS.between(time, now) <= 7 ? RECENT_BONUS : 0;
    }

    private int normalizeLimit(int limit) {
        if (limit <= 0) return DEFAULT_LIMIT;
        if (limit > MAX_LIMIT) {
            throw new BizException(ErrorCode.INVALID_PARAMETER,
                    "每组条数不能超过 " + MAX_LIMIT + "，当前 " + limit);
        }
        return limit;
    }

    private String normalizeScope(String scope) {
        if (scope == null || scope.trim().isEmpty()) return "all";
        String value = scope.trim().toLowerCase();
        if (!"all".equals(value) && !"active".equals(value)) {
            throw new BizException(ErrorCode.INVALID_PARAMETER,
                    "scope 只支持 all（含回收站）或 active（仅活数据），收到「" + scope + "」");
        }
        return value;
    }

    /**
     * 解析类型过滤。缺省 = 全部；非法值连合法取值一起报出来（只报「参数不正确」调用方只能靠猜）。
     */
    private Set<String> parseTypes(String types) {
        if (types == null || types.trim().isEmpty()) return new LinkedHashSet<String>(ALL_TYPES);
        Set<String> result = new LinkedHashSet<String>();
        for (String raw : types.split(",")) {
            String type = raw.trim().toLowerCase();
            if (type.isEmpty()) continue;
            if (!ALL_TYPES.contains(type)) {
                throw new BizException(ErrorCode.INVALID_PARAMETER,
                        "不支持的搜索类型「" + type + "」，只支持：" + String.join(" / ", ALL_TYPES));
            }
            result.add(type);
        }
        // 全是空段（如 ",,"）与缺省等价，不报错
        return result.isEmpty() ? new LinkedHashSet<String>(ALL_TYPES) : result;
    }

    /**
     * 解析语义扩展词（逗号分隔）。
     *
     * <p>三道清洗：去掉与主检索词重合的（否则同一条记录会被算两次命中、结果里出现重复）、
     * 去重、限长限个数。前端把 {@code /search/expand} 拿到的词原样拼进来，
     * 所以这里不能假设它们是干净的。</p>
     */
    private List<String> parseExpandTerms(String expand, List<String> terms) {
        List<String> result = new ArrayList<String>();
        if (expand == null || expand.trim().isEmpty()) return result;
        Set<String> primary = new LinkedHashSet<String>(terms);
        for (String raw : expand.split(",")) {
            String term = raw.trim().toLowerCase();
            if (term.isEmpty() || term.length() > 20) continue;
            if (primary.contains(term) || result.contains(term)) continue;
            result.add(term);
            if (result.size() >= MAX_EXPAND_TERMS) break;
        }
        return result;
    }

    private String timezone() {
        String timezone = configRepository.findValue(AuthConstants.CONFIG_TIMEZONE);
        return timezone == null ? "Asia/Shanghai" : timezone;
    }

    private static String day(String dateTime) {
        return dateTime == null || dateTime.length() < 10 ? null : dateTime.substring(0, 10);
    }

    /** 一条候选结果 + 它的分数（分数不对外暴露，只用于排序）。 */
    private static final class Row {
        final String entity;
        final long id;
        final String updatedAt;
        final int score;
        final SearchItemVO item;

        Row(String entity, long id, String updatedAt, int score, SearchItemVO item) {
            this.entity = entity;
            this.id = id;
            this.updatedAt = updatedAt;
            this.score = score;
            this.item = item;
        }
    }

    private static final class Match {
        List<String> terms = new ArrayList<String>();
        String kind = SearchItemVO.KIND_SEMI;
        String field = SearchItemVO.FIELD_BODY;
        String column;
        int score;
    }

    /** 一个可检索字段：列名 + 层级 + 对外名称。 */
    private static final class Field {
        final String column;
        final int tier;
        final String name;

        Field(String column, int tier, String name) {
            this.column = column;
            this.tier = tier;
            this.name = name;
        }
    }

    /**
     * 一种实体的检索规格。
     *
     * <p>与 {@code TrashService.EntitySpec} 是同一个套路（集中声明、子类补差异），
     * 但两者关心的事情不同：回收站只读「标题 + 摘要」，搜索还要读检索字段、元信息与落点。
     * 所以没有强行抽成一个共用类 —— 硬凑会让两边都多出一堆用不上的抽象方法。</p>
     */
    private abstract static class EntitySpec {
        final String type;
        final String label;
        final String table;
        final String selectColumns;
        final String titleColumn;
        final String orderColumn;
        final boolean softDelete;
        List<Field> fields = new ArrayList<Field>();
        /** 实体自己的业务范围条件（可空）。只在查活数据时生效，见 {@link #query}。 */
        String extraWhere;

        EntitySpec(String type, String label, String table, String selectColumns,
                   String titleColumn, String orderColumn, boolean softDelete) {
            this.type = type;
            this.label = label;
            this.table = table;
            this.selectColumns = selectColumns;
            this.titleColumn = titleColumn;
            this.orderColumn = orderColumn;
            this.softDelete = softDelete;
        }

        abstract String title(ResultSet rs) throws SQLException;

        abstract Map<String, Object> meta(ResultSet rs) throws SQLException;

        abstract SearchTargetVO target(ResultSet rs) throws SQLException;

        /** 是否需要降权（已归档备忘 / 已取消任务）。只影响排序，不隐藏。 */
        abstract boolean demoted(ResultSet rs) throws SQLException;
    }
}
