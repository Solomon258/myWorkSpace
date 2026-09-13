package com.icecode.workbench.transfer;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.icecode.workbench.auth.AppConfigRepository;
import com.icecode.workbench.auth.AuthConstants;
import com.icecode.workbench.common.BizException;
import com.icecode.workbench.common.ErrorCode;
import com.icecode.workbench.memo.MemoRepository;
import com.icecode.workbench.memo.MemoService;
import com.icecode.workbench.memo.MemoVO;
import com.icecode.workbench.schedule.EventRepository;
import com.icecode.workbench.schedule.EventService;
import com.icecode.workbench.schedule.EventVO;
import com.icecode.workbench.task.TaskCreateRequest;
import com.icecode.workbench.task.TaskRepository;
import com.icecode.workbench.task.TaskService;
import com.icecode.workbench.task.TaskVO;
import com.icecode.workbench.util.TextUtil;
import com.icecode.workbench.util.TimeUtil;

/**
 * 「移至」：把一条记录从任务 / 日程 / 备忘中的一个菜单搬到另一个菜单。
 *
 * <p>三张表的列不是一一对应的，「移动」的实际语义是：**在目标表里新建一条 + 把源记录软删
 * （进回收站，30 天内可恢复）+ 记一条流水**，整个动作在一个事务里完成 —— 要么三件事都成，
 * 要么都不成，不会出现「两个菜单里都有」或「两边都没有」的中间态。</p>
 *
 * <p>两条不能省的约束（都是本项目反复踩过的坑）：</p>
 * <ol>
 *   <li><b>不能静默丢字段。</b>任务的优先级、日程的时间、备忘的标签/链接在目标表里没有对应列，
 *       丢掉是必然的，但必须写进 {@code warnings} 让界面告诉用户 ——
 *       用户以为「移过去东西还在」，过几天找不到，才是真正的数据损失。</li>
 *   <li><b>示例数据标记要跟着走</b>（{@code is_demo}）。否则「清空示例数据」之后，
 *       从示例记录移过来的那一条会留在库里：用户看到「清空完成」却还剩一条，只会怀疑功能坏了。</li>
 * </ol>
 *
 * <p>字段留空在这里同样是显式语义：任务的截止日期为空 → 移到日程就是**待定时间**（US-4.2），
 * 不默认今天；到任务的日期同理，没有就是没有。前端负责给新建表单预填默认值，后端不代劳。</p>
 */
@Service
public class TransferService {

    private static final Map<String, String> LABELS;

    static {
        Map<String, String> labels = new LinkedHashMap<String, String>();
        labels.put("task", "任务");
        labels.put("event", "日程");
        labels.put("memo", "备忘");
        LABELS = Collections.unmodifiableMap(labels);
    }

    /** 与 TaskRepository / TaskUpdateRequest 的上限保持一致，超长时截断并警告。 */
    private static final int TASK_DESCRIPTION_MAX = 2000;

    private final TaskService taskService;
    private final EventService eventService;
    private final MemoService memoService;
    private final TaskRepository taskRepository;
    private final EventRepository eventRepository;
    private final MemoRepository memoRepository;
    private final AppConfigRepository configRepository;

    public TransferService(TaskService taskService, EventService eventService, MemoService memoService,
                           TaskRepository taskRepository, EventRepository eventRepository,
                           MemoRepository memoRepository, AppConfigRepository configRepository) {
        this.taskService = taskService;
        this.eventService = eventService;
        this.memoService = memoService;
        this.taskRepository = taskRepository;
        this.eventRepository = eventRepository;
        this.memoRepository = memoRepository;
        this.configRepository = configRepository;
    }

    @Transactional(rollbackFor = Exception.class)
    public TransferResultVO transfer(TransferRequest request) {
        String from = request.getFromType();
        String to = request.getToType();
        if (from.equals(to)) {
            throw new BizException(ErrorCode.INVALID_PARAMETER,
                    "这条记录已经在「" + label(to) + "」里了，请选择「" + otherLabels(to) + "」");
        }
        List<String> warnings = new ArrayList<String>();
        String now = now();
        long newId;
        String title;
        if ("task".equals(from)) {
            TaskVO task = taskService.get(request.getId());
            title = task.getTitle();
            newId = "event".equals(to) ? createEventFromTask(task, warnings, now)
                    : createMemoFromTask(task, warnings, now);
            taskRepository.softDelete(request.getId(), now);
        } else if ("event".equals(from)) {
            EventVO event = eventService.get(request.getId());
            title = event.getTitle();
            newId = "task".equals(to) ? createTaskFromEvent(event, warnings, now)
                    : createMemoFromEvent(event, warnings, now);
            eventRepository.softDelete(request.getId(), now);
        } else {
            MemoVO memo = memoService.get(request.getId());
            title = memo.getTitle();
            newId = "task".equals(to) ? createTaskFromMemo(memo, warnings, now)
                    : createEventFromMemo(memo, warnings, now);
            memoRepository.softDelete(request.getId(), now);
        }
        return new TransferResultVO(from, to, label(from), label(to), newId, title, warnings);
    }

    // ---------- 任务 → 日程 / 备忘 ----------

    private long createEventFromTask(TaskVO task, List<String> warnings, String now) {
        List<String> lost = new ArrayList<String>();
        if (task.getDescription() != null) lost.add("任务描述");
        if (task.getNote() != null) lost.add("备注");
        if (!"todo".equals(task.getStatus())) lost.add("状态（" + statusName(task.getStatus()) + "）");
        if (!"P2".equals(task.getPriority())) lost.add("优先级 " + task.getPriority());
        if (task.isDeep() || task.isBlocking()) lost.add("深度工作 / 阻塞他人的标记");
        if (!lost.isEmpty()) warnings.add("日程没有「" + join(lost, "、") + "」这些字段，移动后不再保留。");
        // 日期为空 = 待定时间，这是真实的第三种状态，必须说清楚它去了哪儿，否则用户以为日程丢了。
        if (task.getDue() == null) {
            warnings.add("这条任务没有截止日期，移到日程后会先落到「待定时间」区，补上日期即可排进那一天。");
        }
        long id = eventRepository.insert(task.getTitle(), "other", task.getDue(), null, null, now,
                task.isDemo(), task.getSourceInboxId());
        eventRepository.insertActivity("task", "从「任务」移至「日程」：" + task.getTitle(), now);
        return id;
    }

    private long createMemoFromTask(TaskVO task, List<String> warnings, String now) {
        List<String> parts = new ArrayList<String>();
        if (task.getDescription() != null) parts.add(task.getDescription());
        if (task.getNote() != null) parts.add(task.getNote());
        // 备忘正文是必填的：任务没写描述也没写备注时，用标题当正文（否则只能凭空造一句）。
        String content = parts.isEmpty() ? task.getTitle() : join(parts, "\n\n");
        List<String> lost = new ArrayList<String>();
        if (!"todo".equals(task.getStatus())) lost.add("状态（" + statusName(task.getStatus()) + "）");
        if (!"P2".equals(task.getPriority())) lost.add("优先级 " + task.getPriority());
        if (task.getDue() != null) lost.add("截止日期 " + task.getDue());
        if (task.isDeep() || task.isBlocking()) lost.add("深度工作 / 阻塞他人的标记");
        if (!lost.isEmpty()) {
            warnings.add("备忘没有「" + join(lost, "、") + "」这些字段，移动后不再保留。");
        }
        // 分组、标签、链接沿用备忘自己的规则（MemoService.autoGroup / autoTags / extractUrl），
        // 不在这里另写一份：同一套规则两处实现，早晚会漂。
        String grp = memoService.autoGroup(content);
        List<String> tags = memoService.autoTags(content, grp);
        String url = memoService.extractUrl(content);
        long id = memoRepository.insert(task.getTitle(), content, url, writeTags(tags), grp, now,
                task.isDemo(), task.getSourceInboxId());
        memoRepository.insertActivity("memo", grp, "从「任务」移至「备忘」：" + task.getTitle(), now);
        return id;
    }

    // ---------- 日程 → 任务 / 备忘 ----------

    private long createTaskFromEvent(EventVO event, List<String> warnings, String now) {
        List<String> lost = new ArrayList<String>();
        if (event.getStart() != null) lost.add("开始时间 " + event.getStart());
        if (event.getEnd() != null) lost.add("结束时间 " + event.getEnd());
        if (!"other".equals(event.getType())) lost.add("日程类型（" + eventTypeName(event.getType()) + "）");
        if (!lost.isEmpty()) warnings.add("任务没有「" + join(lost, "、") + "」这些字段，移动后不再保留。");
        if (event.getDate() == null) warnings.add("这条日程时间待定，移到任务后也不会有截止日期。");

        TaskCreateRequest request = new TaskCreateRequest();
        request.setTitle(event.getTitle());
        request.setPriority("P2");
        request.setDue(event.getDate());
        long id = taskRepository.insert(request, now, event.isDemo(), event.getSourceInboxId());
        taskRepository.insertActivity("task", "从「日程」移至「任务」：" + event.getTitle(), now);
        return id;
    }

    private long createMemoFromEvent(EventVO event, List<String> warnings, String now) {
        // 日程的时间和类型在备忘里没有列，但它们是这条记录的核心信息，直接丢掉不划算：
        // 拼进正文里，用户翻备忘时还能看到「这条原来定在周四 9:30」。
        String content = event.getTitle() + "\n\n（来自日程：" + describeWhen(event) + "）";
        List<String> lost = new ArrayList<String>();
        if (!"other".equals(event.getType())) lost.add("日程类型（" + eventTypeName(event.getType()) + "）");
        if (!lost.isEmpty()) warnings.add("备忘没有「" + join(lost, "、") + "」这些字段，移动后不再保留。");
        String grp = memoService.autoGroup(content);
        List<String> tags = memoService.autoTags(content, grp);
        long id = memoRepository.insert(event.getTitle(), content, memoService.extractUrl(content),
                writeTags(tags), grp, now, event.isDemo(), event.getSourceInboxId());
        memoRepository.insertActivity("memo", grp, "从「日程」移至「备忘」：" + event.getTitle(), now);
        return id;
    }

    // ---------- 备忘 → 任务 / 日程 ----------

    private long createTaskFromMemo(MemoVO memo, List<String> warnings, String now) {
        TaskCreateRequest request = new TaskCreateRequest();
        request.setTitle(memo.getTitle());
        request.setPriority("P2");
        String content = memo.getContent();
        // 备忘的正文常常就是标题（MemoService.shortTitle 从正文裁 24 字），那就不必再往描述里塞一遍，
        // 否则任务卡片里同一句话会显示两行。
        if (content != null && !content.equals(memo.getTitle())) {
            if (content.length() > TASK_DESCRIPTION_MAX) {
                request.setDescription(TextUtil.clip(content, TASK_DESCRIPTION_MAX));
                warnings.add("备忘正文有 " + content.length() + " 字，任务的描述上限是 2000 字，超出的部分不会保留。");
            } else {
                request.setDescription(content);
            }
        }
        addMemoOnlyFieldsWarning(memo, warnings, "任务");
        long id = taskRepository.insert(request, now, memo.isDemo(), memo.getSourceInboxId());
        taskRepository.insertActivity("task", "从「备忘」移至「任务」：" + memo.getTitle(), now);
        return id;
    }

    private long createEventFromMemo(MemoVO memo, List<String> warnings, String now) {
        List<String> lost = new ArrayList<String>();
        if (memo.getContent() != null && !memo.getContent().equals(memo.getTitle())) {
            // 日程只有标题一个文本字段（上限 200 字），长正文必然被截掉。
            // 这里不能说成「已保留」——只能说清它去哪儿了更合适（任务有描述字段）。
            warnings.add("备忘正文在日程里没有对应字段，移动后不再保留；若正文重要，建议改移到「任务」（那里有描述字段）。");
        }
        addMemoOnlyFieldsWarning(memo, warnings, "日程");
        warnings.add("备忘没有日期，移到日程后会先落到「待定时间」区，补上日期即可排进那一天。");
        long id = eventRepository.insert(memo.getTitle(), "other", null, null, null, now,
                memo.isDemo(), memo.getSourceInboxId());
        eventRepository.insertActivity("task", "从「备忘」移至「日程」：" + memo.getTitle(), now);
        return id;
    }

    /** 备忘特有、其它两个菜单都没有的字段：有值才提示，没填过就不必拿它占用户的眼睛。 */
    private void addMemoOnlyFieldsWarning(MemoVO memo, List<String> warnings, String target) {
        List<String> lost = new ArrayList<String>();
        if (memo.getTags() != null && !memo.getTags().isEmpty()) lost.add("标签（" + join(memo.getTags(), "、") + "）");
        if (memo.getUrl() != null) lost.add("链接");
        if (memo.isPinned()) lost.add("置顶");
        if ("archived".equals(memo.getStatus())) lost.add("已归档状态");
        if (!lost.isEmpty()) {
            warnings.add(target + "没有「" + join(lost, "、") + "」这些字段，移动后不再保留。");
        }
    }

    // ---------- 工具 ----------

    private String describeWhen(EventVO event) {
        if (event.getDate() == null) return "时间待定";
        StringBuilder when = new StringBuilder(event.getDate());
        if (event.getStart() != null) {
            when.append(" ").append(event.getStart());
            if (event.getEnd() != null) when.append("-").append(event.getEnd());
        }
        return when.toString();
    }

    /** 标签的 JSON 写法在 MemoService 里只有一处（writeTags），这里直接复用，不再抄一份。 */
    private String writeTags(List<String> tags) { return memoService.writeTags(tags); }

    private String label(String type) {
        String value = LABELS.get(type);
        return value == null ? type : value;
    }

    /** 「任务」→「日程 或 备忘」，用于「已经在某个菜单里」的提示，让用户知道下一步该点哪个。 */
    private String otherLabels(String type) {
        List<String> others = new ArrayList<String>();
        for (Map.Entry<String, String> entry : LABELS.entrySet()) {
            if (!entry.getKey().equals(type)) others.add(entry.getValue());
        }
        return join(others, "」或「");
    }

    private String statusName(String status) {
        if ("todo".equals(status)) return "待办";
        if ("doing".equals(status)) return "进行中";
        if ("done".equals(status)) return "已完成";
        return "已取消";
    }

    private String eventTypeName(String type) {
        if ("meeting".equals(type)) return "会议";
        if ("deep_block".equals(type)) return "深度块";
        return "其他";
    }

    private String join(List<String> values, String separator) {
        StringBuilder result = new StringBuilder();
        for (String value : values) {
            if (result.length() > 0) result.append(separator);
            result.append(value);
        }
        return result.toString();
    }

    private String timezone() {
        String timezone = configRepository.findValue(AuthConstants.CONFIG_TIMEZONE);
        return timezone == null ? "Asia/Shanghai" : timezone;
    }

    private String now() { return TimeUtil.now(timezone()); }
}
