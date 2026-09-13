package com.icecode.workbench.inbox;

import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.icecode.workbench.auth.AppConfigRepository;
import com.icecode.workbench.auth.AuthConstants;
import com.icecode.workbench.common.BizException;
import com.icecode.workbench.common.ErrorCode;
import com.icecode.workbench.util.TimeUtil;

@Service
public class InboxService {

    private static final double AUTO_CONFIRM_THRESHOLD = 0.70;

    private final InboxRepository inboxRepository;
    private final ClassifyRouter classifyService;
    private final AppConfigRepository configRepository;
    private final JdbcTemplate jdbcTemplate;
    private final com.icecode.workbench.knowledge.KnowledgeService knowledgeService;
    // 日程一律走 EventRepository 写入：「收录确认」与「加入日程」是两条不同的入口，
    // 而「物化重复日程」的逻辑只能有一份（见 EventRepository.insertRepeating）。
    private final com.icecode.workbench.schedule.EventRepository eventRepository;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public InboxService(InboxRepository inboxRepository, ClassifyRouter classifyService,
                        AppConfigRepository configRepository, JdbcTemplate jdbcTemplate,
                        com.icecode.workbench.knowledge.KnowledgeService knowledgeService,
                        com.icecode.workbench.schedule.EventRepository eventRepository) {
        this.inboxRepository = inboxRepository;
        this.classifyService = classifyService;
        this.configRepository = configRepository;
        this.jdbcTemplate = jdbcTemplate;
        this.knowledgeService = knowledgeService;
        this.eventRepository = eventRepository;
    }

    public List<InboxVO> list(String status) {
        validateOptionalStatus(status);
        List<InboxVO> result = new ArrayList<InboxVO>();
        for (InboxRecord record : inboxRepository.findByStatus(status)) result.add(toVO(record));
        return result;
    }

    @Transactional(rollbackFor = Exception.class)
    public InboxVO create(InboxCreateRequest request) {
        String raw = requireRaw(request.getRaw());
        String contentType = request.getContentType() == null ? "text" : request.getContentType();
        String source = request.getSource() == null ? "web" : request.getSource();
        String now = now();
        long id = inboxRepository.insert(raw, contentType, source, now);
        inboxRepository.insertActivity("inbox", "收录「" + shortText(raw) + "」", now);
        return get(id);
    }

    @Transactional(rollbackFor = Exception.class)
    public int classifyPending() {
        String timezone = timezone();
        String now = TimeUtil.now(timezone);
        int processed = 0;
        for (InboxRecord record : inboxRepository.findPending()) {
            try {
                ClassifySuggestionVO suggestion = classifyService.classify(record.raw, timezone);
                inboxRepository.markClassified(record.id, suggestion.getCategory(), suggestion.getConfidence(),
                        writePayload(suggestion.getPayload()), now);
                processed++;
            } catch (Exception exception) {
                inboxRepository.markFailed(record.id, now);
            }
        }
        if (processed > 0) inboxRepository.insertActivity("task", "整理完成 " + processed + " 条，等待确认", now);
        return processed;
    }

    @Transactional(rollbackFor = Exception.class)
    public InboxVO reclassify(long id) {
        InboxRecord record = requireInbox(id);
        if ("archived".equals(record.status)) throw new BizException(ErrorCode.INVALID_PARAMETER, "该条目已归档，不需要重新整理");
        ClassifySuggestionVO suggestion = classifyService.classify(record.raw, timezone());
        inboxRepository.markClassified(id, suggestion.getCategory(), suggestion.getConfidence(),
                writePayload(suggestion.getPayload()), now());
        return get(id);
    }

    @Transactional(rollbackFor = Exception.class)
    public void delete(long id) {
        InboxRecord record = requireInbox(id);
        String now = now();
        inboxRepository.softDelete(id, now);
        inboxRepository.insertActivity("inbox", "删除收集箱条目「" + shortText(record.raw) + "」", now);
    }

    @Transactional(rollbackFor = Exception.class)
    public ConfirmResultVO confirm(long id, InboxConfirmRequest request) {
        InboxRecord record = requireInbox(id);
        if ("archived".equals(record.status)) {
            Long existing = findExistingEntity(id, request.getCategory());
            if (existing != null) return new ConfirmResultVO(id, request.getCategory(), existing.longValue(), true);
            throw new BizException(ErrorCode.INVALID_PARAMETER,
                    "该条目已归档，但没有找到对应的「" + categoryName(request.getCategory()) + "」记录；可能是对应数据已被删除");
        }
        validateConfirm(record);
        String title = request.getTitle() == null || request.getTitle().trim().isEmpty()
                ? record.raw : request.getTitle().trim();
        String due = normalizeDate(request.getDue());
        String now = now();
        long entityId;
        if ("task".equals(request.getCategory())) {
            entityId = createTask(id, title, normalizePriority(request.getPriority(), record), due, now);
        } else if ("schedule".equals(request.getCategory())) {
            entityId = createEvent(id, title, request, due, now);
        } else if ("knowledge".equals(request.getCategory())) {
            entityId = knowledgeService.create(Long.valueOf(id), title, record.raw).getId();
        } else {
            entityId = createMemo(id, title, record.raw, now);
        }
        inboxRepository.markArchived(id, request.getCategory(), now);
        // 时间待定的日程落到待定区，流水里要说清楚，否则用户会以为日期丢了。
        String pendingNote = "schedule".equals(request.getCategory()) && due == null ? "（时间待定，可在日程页补全）" : "";
        inboxRepository.insertActivity("task", "整理确认：生成「" + categoryName(request.getCategory()) + "」" + title + pendingNote, now);
        return new ConfirmResultVO(id, request.getCategory(), entityId, false);
    }

    /**
     * 一键确认高置信度（整理页的快捷入口，没有表单可填）。
     *
     * <p>日期规则与手动确认保持一致：整理器抽出了日期就照用；抽不出来就**按今天排**。
     * 手动那条路由前端把日期框预填今天（用户可以改，也可以清空表示「时间待定」），
     * 而这条路径用户看不到任何字段，缺省必须是今天 —— 否则批量生成的条目全部没有日期，
     * 既不出现在驾驶舱的今日清单里，也没有任何提示，表现就是「生成了却哪儿都找不到」。</p>
     */
    @Transactional(rollbackFor = Exception.class)
    public List<ConfirmResultVO> confirmHighConfidence() {
        List<ConfirmResultVO> results = new ArrayList<ConfirmResultVO>();
        for (InboxRecord record : inboxRepository.findByStatus("processed")) {
            if (record.aiConfidence == null || record.aiConfidence.doubleValue() < AUTO_CONFIRM_THRESHOLD) continue;
            // 未配置 Vault 时跳过知识类，避免自动确认报错；用户配置后可在整理页手动确认
            if ("knowledge".equals(record.aiCategory) && !knowledgeService.vaultConfigured()) continue;
            ClassifyPayload payload = readPayload(record.aiPayload);
            InboxConfirmRequest request = new InboxConfirmRequest();
            request.setCategory(record.aiCategory);
            request.setTitle(payload.getTitle());
            request.setDue(defaultToToday(payload.getDue()));
            request.setPriority(payload.getPriority());
            request.setStart(payload.getStart());
            request.setEnd(payload.getEnd());
            request.setEventType(payload.getEventType());
            results.add(confirm(record.id, request));
        }
        return results;
    }

    /** 日期留空时按「今天」排（今天由配置时区算出，与驾驶舱、日程页用的是同一个定义）。 */
    private String defaultToToday(String date) {
        return date == null || date.trim().isEmpty() ? TimeUtil.format(TimeUtil.localDate(timezone())) : date;
    }

    public InboxVO get(long id) { return toVO(requireInbox(id)); }

    private void validateConfirm(InboxRecord record) {
        // 注意：ai_confidence < 0.70 只表示「不参与批量自动确认」（见 confirmHighConfidence），
        // 不代表用户不能手动确认。用户在整理页点「确认生成」本身就是人工确认，
        // 即使沿用 AI 建议的类别也必须放行——否则规则整理器默认输出的 task@0.58 会让
        // 这类条目永远无法归档，形成没有出口的死路。
        if (!"processed".equals(record.status) && !"failed".equals(record.status)) {
            throw new BizException(ErrorCode.INVALID_PARAMETER,
                    "该条目当前状态是「" + statusName(record.status) + "」，只有「待确认」或「整理失败」的条目可以确认生成");
        }
        // 日程不再强制要求日期：日期空缺按 US-4.2 落到「待定时间」区，由用户在日程页人工补全。
        // （V6 之前 schedule_event.event_date 是 NOT NULL，这里只能报「必须填写日期」把用户堵住。）
    }

    private Long findExistingEntity(long inboxId, String category) {
        String table = "task".equals(category) ? "task" : "schedule".equals(category) ? "schedule_event" : "memo";
        List<Long> ids = jdbcTemplate.queryForList("SELECT id FROM " + table + " WHERE source_inbox_id=? AND deleted=0 ORDER BY id", Long.class, inboxId);
        return ids.isEmpty() ? null : ids.get(0);
    }

    private long createTask(long inboxId, String title, String priority, String due, String now) {
        jdbcTemplate.update("INSERT INTO task(title, priority, status, due_date, is_deep_work, is_blocking, source_inbox_id, created_at, updated_at, is_demo) VALUES (?,?, 'todo', ?, 0, 0, ?, ?, ?, 0)",
                title, priority, due, inboxId, now, now);
        return jdbcTemplate.queryForObject("SELECT id FROM task WHERE source_inbox_id=? ORDER BY id DESC LIMIT 1", Long.class, inboxId).longValue();
    }

    private long createEvent(long inboxId, String title, InboxConfirmRequest request, String due, String now) {
        String type = request.getEventType() == null ? "meeting" : request.getEventType();
        String start = emptyToNull(request.getStart());
        String end = emptyToNull(request.getEnd());
        if (start != null && end != null && end.compareTo(start) <= 0) throw new BizException(ErrorCode.INVALID_EVENT_TIME);
        // due 为 null 即「时间待定」，照样入库，稍后在日程页的待定区里由人工补全（US-4.2）。
        // 「每周 X」识别出的重复期数在这里生效：物化成 N 条连续周的同一天（见 insertRepeating）。
        // 待定日程没有「每周的同一天」可言，一律按单期写入；返回的是**首期** id。
        int weeks = (due == null || request.getRepeatWeeks() == null) ? 1 : request.getRepeatWeeks().intValue();
        return eventRepository.insertRepeating(title, type, due, start, end, now, false, Long.valueOf(inboxId), weeks);
    }

    private long createMemo(long inboxId, String title, String raw, String now) {
        String url = extractUrl(raw);
        String tags = extractTags(raw);
        String group = extractGroup(raw);
        jdbcTemplate.update("INSERT INTO memo(title, content, url, tags, grp, pinned, status, source_inbox_id, created_at, updated_at, is_demo) VALUES (?,?,?,?,?,0,'active',?,?,?,0)",
                title, raw, url, tags, group, inboxId, now, now);
        return jdbcTemplate.queryForObject("SELECT id FROM memo WHERE source_inbox_id=? ORDER BY id DESC LIMIT 1", Long.class, inboxId).longValue();
    }

    private InboxVO toVO(InboxRecord record) {
        ClassifySuggestionVO ai = null;
        if (record.aiCategory != null && record.aiConfidence != null) {
            ClassifyPayload payload = readPayload(record.aiPayload);
            ai = new ClassifySuggestionVO(record.aiCategory, record.aiConfidence.doubleValue(), payload,
                    record.aiConfidence.doubleValue() < AUTO_CONFIRM_THRESHOLD);
        }
        return new InboxVO(record.id, record.raw, record.contentType, record.source, record.status,
                record.createdAt, record.processedAt, ai);
    }

    private InboxRecord requireInbox(long id) {
        InboxRecord record = inboxRepository.findById(id);
        if (record == null) throw new BizException(ErrorCode.RESOURCE_NOT_FOUND);
        return record;
    }

    private String requireRaw(String raw) {
        String value = raw == null ? "" : raw.trim();
        if (value.isEmpty() || value.length() > 4000) throw new BizException(ErrorCode.INVALID_PARAMETER, "收录内容不能为空，且不能超过 4000 字");
        return value;
    }

    private String normalizePriority(String priority, InboxRecord record) {
        if (priority != null && !priority.trim().isEmpty()) return priority.trim().toUpperCase();
        return readPayload(record.aiPayload).getPriority() == null ? "P2" : readPayload(record.aiPayload).getPriority();
    }

    private String normalizeDate(String date) {
        if (date == null || date.trim().isEmpty()) return null;
        try { return TimeUtil.format(LocalDate.parse(date.trim())); }
        catch (DateTimeParseException exception) {
            throw new BizException(ErrorCode.INVALID_PARAMETER, "日期格式不正确，应为 YYYY-MM-DD");
        }
    }

    private String writePayload(ClassifyPayload payload) {
        try { return objectMapper.writeValueAsString(payload); }
        catch (Exception exception) { throw new BizException(ErrorCode.INTERNAL_ERROR); }
    }

    private ClassifyPayload readPayload(String json) {
        if (json == null || json.trim().isEmpty()) return new ClassifyPayload();
        try { return objectMapper.readValue(json, new TypeReference<ClassifyPayload>() { }); }
        catch (Exception exception) { return new ClassifyPayload(); }
    }

    private String extractUrl(String raw) {
        java.util.regex.Matcher matcher = java.util.regex.Pattern.compile("https?://\\S+").matcher(raw);
        return matcher.find() ? matcher.group() : null;
    }
    private String extractTags(String raw) {
        List<String> tags = new ArrayList<String>();
        if (raw.matches(".*(买|购|奶粉|菜|超市|快递).*")) tags.add("家庭采购");
        if (raw.matches(".*(班车|通勤|地铁|停车).*")) tags.add("通勤");
        if (extractUrl(raw) != null) tags.add("链接收藏");
        if (tags.isEmpty()) tags.add("整理确认");
        try { return objectMapper.writeValueAsString(tags); } catch (Exception exception) { return "[\"整理确认\"]"; }
    }
    private String extractGroup(String raw) {
        return raw.matches(".*(评审|会议|方案|MR|代码|服务器|需求|Jira|GitLab|项目|客户|业务方|报销|发票|运维|VPN|值班|Obsidian|技术).*") ? "work" : "life";
    }

    private void validateOptionalStatus(String status) {
        if (status == null || status.trim().isEmpty()) return;
        if (!("pending".equals(status) || "processed".equals(status) || "failed".equals(status) || "archived".equals(status))) {
            throw new BizException(ErrorCode.INVALID_PARAMETER, "状态筛选只能填 pending / processed / failed / archived");
        }
    }
    private String timezone() { String timezone = configRepository.findValue(AuthConstants.CONFIG_TIMEZONE); return timezone == null ? "Asia/Shanghai" : timezone; }
    private String now() { return TimeUtil.now(timezone()); }
    private String shortText(String text) { return text.length() <= 30 ? text : text.substring(0, 30) + "…"; }
    private String categoryName(String category) { return "task".equals(category) ? "任务" : "schedule".equals(category) ? "日程" : "knowledge".equals(category) ? "知识" : "备忘"; }
    private String statusName(String status) {
        if ("pending".equals(status)) return "待整理";
        if ("processed".equals(status)) return "待确认";
        if ("failed".equals(status)) return "整理失败";
        if ("archived".equals(status)) return "已归档";
        return status == null ? "未知" : status;
    }
    private String emptyToNull(String value) { return value == null || value.trim().isEmpty() ? null : value; }
}
