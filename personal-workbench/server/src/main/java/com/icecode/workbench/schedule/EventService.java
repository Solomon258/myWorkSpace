package com.icecode.workbench.schedule;

import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.icecode.workbench.attachment.AttachmentService;
import com.icecode.workbench.auth.AppConfigRepository;
import com.icecode.workbench.auth.AuthConstants;
import com.icecode.workbench.common.BizException;
import com.icecode.workbench.common.ErrorCode;
import com.icecode.workbench.util.TimeUtil;

@Service
public class EventService {

    /** 重复日程的期数上限；`EventCreateRequest.repeatWeeks` 的 @Max 与它对齐。 */
    private static final int MAX_REPEAT_WEEKS = 12;

    private final EventRepository eventRepository;
    private final AppConfigRepository configRepository;
    private final AttachmentService attachmentService;

    public EventService(EventRepository eventRepository, AppConfigRepository configRepository,
                        AttachmentService attachmentService) {
        this.eventRepository = eventRepository;
        this.configRepository = configRepository;
        this.attachmentService = attachmentService;
    }

    public List<EventVO> list(String date) {
        String targetDate = date == null || date.trim().isEmpty() ? TimeUtil.today(timezone()) : normalizeDate(date);
        List<EventVO> result = new ArrayList<EventVO>();
        for (EventRecord record : eventRepository.findByDate(targetDate)) result.add(toVO(record));
        return result;
    }

    /**
     * 区间查询（日程页周视图，见 {@code docs/日程周视图设计.md}）：一次取回一整周，
     * 前端不必按天循环调 7 次接口（那会让一次页面刷新多出 6 个请求）。
     *
     * <p>{@code from} 与 {@code to} 必须**成对**给出，缺一个直接报错而不是「另一半默认等于自己」：
     * 那种隐式兜底会让用户少打一个参数却拿到一个看起来正常、实际不是他要的区间，
     * 界面上没有任何迹象可循。这也是本项目一贯的分工 —— 缺省值放前端显式填，后端不猜。</p>
     *
     * <p>首尾两天都包含在内（闭区间）：七行视图里少了周一或周日，用户会以为那天没日程。</p>
     */
    public List<EventVO> listRange(String from, String to) {
        if (from == null || from.trim().isEmpty() || to == null || to.trim().isEmpty()) {
            throw new BizException(ErrorCode.INVALID_PARAMETER,
                    "区间查询需要同时提供 from 与 to，例如 from=2026-09-07&to=2026-09-13");
        }
        String start = normalizeDate(from);
        String end = normalizeDate(to);
        if (start.compareTo(end) > 0) {
            throw new BizException(ErrorCode.INVALID_PARAMETER,
                    "开始日期不能晚于结束日期（from=" + start + "，to=" + end + "）");
        }
        List<EventVO> result = new ArrayList<EventVO>();
        for (EventRecord record : eventRepository.findByRange(start, end)) result.add(toVO(record));
        return result;
    }

    /** 待定时间区（US-4.2）：日期未定的日程单独成列，供人工补全后归队。 */
    public List<EventVO> listPending() {
        List<EventVO> result = new ArrayList<EventVO>();
        for (EventRecord record : eventRepository.findPending()) result.add(toVO(record));
        return result;
    }

    /**
     * 全文检索（跨日期，含待定区）。命中范围与排序理由见 {@code EventRepository.search}。
     *
     * <p>关键词为空白时返回空列表而不是「全部日程」：空串被当成「搜全部」的话，
     * 界面上清空搜索框的那一刻会闪出一整库的日程，看起来像搜索坏了。</p>
     */
    public List<EventVO> search(String keyword) {
        List<EventVO> result = new ArrayList<EventVO>();
        if (keyword == null || keyword.trim().isEmpty()) return result;
        for (EventRecord record : eventRepository.search(keyword)) result.add(toVO(record));
        return result;
    }

    @Transactional(rollbackFor = Exception.class)
    public EventSaveResultVO create(EventCreateRequest request) {
        String title = requireTitle(request.getTitle());
        String type = request.getType() == null ? "other" : request.getType();
        // 日期留空 = 时间还没定下来，落到「待定时间」区；前端默认已把日期预填为今天，
        // 所以这里不再用「留空即今天」的隐式兜底——否则用户没有任何办法手工创建一条待定日程。
        String date = normalizeOptionalDate(request.getDate());
        String start = emptyToNull(request.getStart());
        String end = emptyToNull(request.getEnd());
        validateTimeRange(start, end);
        String now = now();
        int weeks = repeatWeeks(request.getRepeatWeeks(), date);
        // 只按**首期**做一次冲突检测：同一组各期是同一时刻的同一件事，
        // 逐期检测只会把同一条警告重复 N 遍，反而盖住真正的新信息。
        List<String> warnings = findConflicts(null, date, start, end);
        long id = eventRepository.insertRepeating(title, type, date, start, end, now, false, null, weeks);
        // 附件只挂到**首期**：重复日程的每一期都是独立记录，把同一批附件复制 N 份
        // 会让「删掉其中一期」时不知道该不该删文件，引用计数也失去意义。
        attachmentService.bindAll(request.getAttachmentIds(), "schedule_event", id);
        eventRepository.insertActivity("task",
                "新增日程「" + title + "」" + describeWhen(date, start) + (weeks > 1 ? "（每周，共 " + weeks + " 期）" : ""),
                now);
        return new EventSaveResultVO(get(id), warnings);
    }

    /**
     * 把「重复几期」收敛成一个安全值。
     *
     * <p>待定日程（{@code date == null}）**一律不重复**：没有日期就谈不上「每周的同一天」，
     * 硬展开只能凭空编一个日期出来。真实路径不会出现这种组合 —— 整理器识别「每周 X」时
     * 会先算出下一个该星期几作为首期日期。</p>
     */
    private int repeatWeeks(Integer requested, String date) {
        if (requested == null || requested.intValue() <= 1 || date == null) return 1;
        return Math.min(requested.intValue(), MAX_REPEAT_WEEKS);
    }

    @Transactional(rollbackFor = Exception.class)
    public EventSaveResultVO update(long id, EventUpdateRequest request) {
        EventRecord event = requireEvent(id);
        if (request.getTitle() != null) event.title = requireTitle(request.getTitle());
        if (request.getType() != null) event.type = request.getType();
        // 清空日期 = 把它放回「待定时间」区。此前空串会走到 normalizeDate("") 直接抛
        // 「日期格式不正确」，用户在编辑面板里根本清不掉日期，也是一条没有出口的死路。
        if (request.getDate() != null) event.date = normalizeOptionalDate(request.getDate());
        if (request.getStart() != null) event.start = emptyToNull(request.getStart());
        if (request.getEnd() != null) event.end = emptyToNull(request.getEnd());
        validateTimeRange(event.start, event.end);
        List<String> warnings = findConflicts(Long.valueOf(id), event.date, event.start, event.end);
        String now = now();
        eventRepository.update(event, now);
        return new EventSaveResultVO(get(id), warnings);
    }

    @Transactional(rollbackFor = Exception.class)
    public void delete(long id) {
        EventRecord event = requireEvent(id);
        String now = now();
        eventRepository.softDelete(id, now);
        eventRepository.insertActivity("task", "删除日程「" + event.title + "」", now);
    }

    public EventVO get(long id) { return toVO(requireEvent(id)); }

    public int countToday() { return eventRepository.countByDate(TimeUtil.today(timezone())); }

    public List<EventVO> todayEvents() { return list(null); }

    private List<String> findConflicts(Long excludeId, String date, String start, String end) {
        List<String> warnings = new ArrayList<String>();
        // 日期或开始时间未定 => 无法判断重叠，直接跳过。待定日程不应该参与冲突检测。
        if (date == null || start == null) return warnings;
        int newStart = minutes(start);
        int newEnd = end == null ? newStart + 60 : minutes(end);
        for (EventRecord other : eventRepository.findByDate(date)) {
            if (excludeId != null && other.id == excludeId.longValue()) continue;
            if (other.start == null) continue;
            int otherStart = minutes(other.start);
            int otherEnd = other.end == null ? otherStart + 60 : minutes(other.end);
            if (newStart < otherEnd && otherStart < newEnd) {
                warnings.add("与「" + other.title + "」（" + other.start + "）时间重叠");
            }
        }
        return warnings;
    }

    private void validateTimeRange(String start, String end) {
        if (start != null && end != null && minutes(end) <= minutes(start)) {
            throw new BizException(ErrorCode.INVALID_EVENT_TIME);
        }
    }

    private int minutes(String time) {
        return Integer.parseInt(time.substring(0, 2)) * 60 + Integer.parseInt(time.substring(3, 5));
    }

    private EventVO toVO(EventRecord record) {
        return new EventVO(record.id, record.title, record.type, record.date, record.start, record.end,
                record.sourceInboxId, record.createdAt, record.demo, record.repeatGroup, record.repeatTotal);
    }

    private EventRecord requireEvent(long id) {
        EventRecord event = eventRepository.findById(id);
        if (event == null) throw new BizException(ErrorCode.RESOURCE_NOT_FOUND);
        return event;
    }

    private String requireTitle(String title) {
        String value = title == null ? "" : title.trim();
        if (value.isEmpty() || value.length() > 200) throw new BizException(ErrorCode.INVALID_PARAMETER, "日程标题不能为空，且不能超过 200 字");
        return value;
    }

    private String normalizeDate(String date) {
        try { return TimeUtil.format(LocalDate.parse(date.trim())); }
        catch (DateTimeParseException exception) { throw new BizException(ErrorCode.INVALID_PARAMETER, "日期格式不正确，请按 YYYY-MM-DD 填写"); }
    }

    /** 日期为空串或 null 都表示「时间待定」，返回 null；只有填了才校验格式。 */
    private String normalizeOptionalDate(String date) {
        String value = emptyToNull(date);
        return value == null ? null : normalizeDate(value);
    }

    /** 时间线文案里的时间后缀：待定日程要显式说明，否则流水里看起来像条没有时间的错误数据。 */
    private String describeWhen(String date, String start) {
        if (date == null) return "（时间待定）";
        return start == null ? "" : " " + start;
    }

    private String timezone() {
        String timezone = configRepository.findValue(AuthConstants.CONFIG_TIMEZONE);
        return timezone == null ? "Asia/Shanghai" : timezone;
    }

    private String now() { return TimeUtil.now(timezone()); }
    private String emptyToNull(String value) { return value == null || value.trim().isEmpty() ? null : value; }
}
