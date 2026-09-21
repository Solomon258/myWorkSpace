package com.icecode.workbench.task;

import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.icecode.workbench.attachment.AttachmentService;
import com.icecode.workbench.attachment.AttachmentVO;
import com.icecode.workbench.auth.AppConfigRepository;
import com.icecode.workbench.auth.AuthConstants;
import com.icecode.workbench.common.BizException;
import com.icecode.workbench.common.ErrorCode;
import com.icecode.workbench.favorite.FavoriteService;
import com.icecode.workbench.util.TimeUtil;

@Service
public class TaskService {

    private static final String[] PRAISES = {
            "干得漂亮，又拿下一城！", "稳！这个节奏保持住。", "漂亮，这事终于落地了。",
            "执行力拉满，向你致敬。", "状态正佳，乘胜追击！", "积小胜为大胜，很好。"
    };

    /** 任务列表的两个排序口径（2026-09-20）。取值同时是 SQL 分支的开关，别改字面量。 */
    private static final String SORT_DUE = "due";
    private static final String SORT_UPDATED = "updated";

    private final TaskRepository taskRepository;
    private final AppConfigRepository configRepository;
    private final FavoriteService favoriteService;
    private final AttachmentService attachmentService;

    /**
     * 工作 / 生活的判定规则复用 {@code FavoriteService.autoGroup}，**不在这里再写一份关键词表**。
     *
     * <p>理由与 {@code TransferService} 复用收藏的标签推导一致：两处各写一份「什么时候算工作」，
     * 迟早会漂 —— 而漂了不报错，只是同一条内容从任务页进来算工作、从收藏页进来算生活，
     * 用户永远说不清哪个才是对的。{@code FavoriteService} 不依赖 task 包，注入不会成环。</p>
     */
    public TaskService(TaskRepository taskRepository, AppConfigRepository configRepository,
                       FavoriteService favoriteService, AttachmentService attachmentService) {
        this.taskRepository = taskRepository;
        this.configRepository = configRepository;
        this.favoriteService = favoriteService;
        this.attachmentService = attachmentService;
    }

    /**
     * 任务列表。{@code sort} 只有两个取值：{@code due}（按截止时间，默认）与
     * {@code updated}（按最后修改时间）。
     *
     * <p>排序必须落在 SQL 里（见 {@code TaskRepository.find}），不能取回来再排：任务页一次只加载
     * 100 条，本地排序只能在这 100 条里做 —— 选「按最后修改时间」的用户要看的是最近改过的那一批，
     * 它们完全可能不在「按截止时间排出来的前 100 条」之内。</p>
     */
    public List<TaskVO> list(String status, String priority, String grp, String keyword, String dueFrom,
                             String dueTo, String sort, int page, int size) {
        validateOptionalStatus(status);
        validateOptionalPriority(priority);
        validateOptionalGroup(grp);
        validateOptionalDate(dueFrom);
        validateOptionalDate(dueTo);
        String order = normalizeSort(sort);
        int safePage = Math.max(1, page);
        int safeSize = Math.min(100, Math.max(1, size));
        LocalDate today = today();
        List<TaskRecord> records = taskRepository.find(status, priority, grp, keyword, dueFrom, dueTo, order, safePage, safeSize);
        // 附件走一次批量查询再按 ownerId 分组（N+1 会把一屏 100 条任务变成 100 次查询，见 AttachmentService）。
        List<Long> ids = new ArrayList<Long>(records.size());
        for (TaskRecord record : records) ids.add(Long.valueOf(record.id));
        Map<Long, List<AttachmentVO>> attachments = attachmentService.mapByOwner("task", ids);
        List<TaskVO> result = new ArrayList<TaskVO>();
        for (TaskRecord record : records) {
            result.add(toVO(record, today, attachments.get(Long.valueOf(record.id))));
        }
        return result;
    }

    @Transactional(rollbackFor = Exception.class)
    public TaskVO create(TaskCreateRequest request) {
        request.setTitle(requireTitle(request.getTitle()));
        request.setPriority(normalizePriority(request.getPriority()));
        request.setDue(normalizeDate(request.getDue()));
        // 分组：没传 / 传 auto 时按标题 + 描述的关键词判定，与收藏页同一套规则。
        // 判定放在**服务层**而不是数据库默认值：默认值是死板的 'work'，
        // 那样「买奶粉」这类生活任务会被静默塞进工作里，用户永远看不到自己漏了什么。
        request.setGrp(resolveCreateGroup(request.getGrp(), request.getTitle(), request.getDescription()));
        String now = now();
        long id = taskRepository.insert(request, now);
        // 附件是「先上传拿 id、提交时才绑定」：上传阶段文件已经落盘校验完毕，
        // 这里只做归属。已挂在别处的附件会被拒（见 AttachmentService.bindAll），
        // 所以整个方法必须在一个事务里 —— 绑定失败要连带任务一起回滚。
        attachmentService.bindAll(request.getAttachmentIds(), "task", id);
        taskRepository.insertActivity("task", "新建任务「" + request.getTitle() + "」（" + request.getPriority() + "）", now);
        return get(id);
    }

    @Transactional(rollbackFor = Exception.class)
    public TaskVO update(long id, TaskUpdateRequest request) {
        TaskRecord task = requireTask(id);
        if (request.getTitle() != null) task.title = requireTitle(request.getTitle());
        if (request.getDescription() != null) task.description = trimToNull(request.getDescription());
        if (request.getPriority() != null) task.priority = normalizePriority(request.getPriority());
        if (request.getDue() != null) task.due = normalizeDate(request.getDue());
        if (request.getDeep() != null) task.deep = request.getDeep().booleanValue();
        if (request.getBlocking() != null) task.blocking = request.getBlocking().booleanValue();
        if (request.getGrp() != null && !request.getGrp().trim().isEmpty()) {
            task.grp = normalizeGroup(request.getGrp());
        }
        if (request.getNote() != null) task.note = trimToNull(request.getNote());
        task.updatedAt = now();
        taskRepository.update(task);
        taskRepository.insertActivity("task", "编辑任务「" + task.title + "」", task.updatedAt);
        return get(id);
    }

    /**
     * 只改分组（卡片上那个可点的「工作 / 生活」徽标走这条路）。
     *
     * <p>为什么不复用 {@code update()}：那条路是「整体覆盖式」的，前端为了改一个分组
     * 就得把标题、优先级、日期、备注全部回传一遍 —— 期间用户要是在另一个窗口改了标题，
     * 这次提交会把旧标题写回去（丢失更新）。改分组就只动分组这一格。</p>
     */
    @Transactional(rollbackFor = Exception.class)
    public TaskVO changeGroup(long id, String targetGroup) {
        TaskRecord task = requireTask(id);
        String group = normalizeGroup(targetGroup);
        if (group.equals(task.grp)) {
            // 幂等：点到自己已经在的那一侧不算错，也不写一条毫无信息量的流水。
            return toVO(task, today());
        }
        task.grp = group;
        task.updatedAt = now();
        taskRepository.update(task);
        taskRepository.insertActivity("task", "任务「" + task.title + "」移到" + groupName(group), task.updatedAt);
        return get(id);
    }

    @Transactional(rollbackFor = Exception.class)
    public TaskVO changeStatus(long id, String targetStatus) {
        TaskRecord task = requireTask(id);
        validateStatus(targetStatus);
        if (!isAllowedTransition(task.status, targetStatus)) {
            throw new BizException(ErrorCode.INVALID_TASK_TRANSITION);
        }
        String now = now();
        String completedAt = "done".equals(targetStatus) ? now : null;
        taskRepository.updateStatus(id, targetStatus, completedAt, now);
        if ("done".equals(targetStatus)) {
            String praise = PRAISES[(int) (id % PRAISES.length)];
            taskRepository.insertActivity("praise", "完成任务「" + task.title + "」— " + praise, now);
        } else {
            taskRepository.insertActivity("task", "任务「" + task.title + "」状态更新为" + statusName(targetStatus), now);
        }
        return get(id);
    }

    @Transactional(rollbackFor = Exception.class)
    public TaskVO postpone(long id) {
        TaskRecord task = requireTask(id);
        if ("done".equals(task.status) || "canceled".equals(task.status)) {
            throw new BizException(ErrorCode.TASK_NOT_ACTIONABLE);
        }
        LocalDate base = task.due == null ? today() : parseDate(task.due);
        String due = TimeUtil.format(base.plusDays(1));
        int postponed = task.postponed + 1;
        String now = now();
        taskRepository.postpone(id, due, postponed, now);
        taskRepository.insertActivity("task", "顺延任务「" + task.title + "」至 " + due + "（第 " + postponed + " 次顺延）", now);
        return get(id);
    }

    /**
     * 复制任务（2026-09-21）：给「昨天做了、今天还得做」这类事一条快捷路径。
     *
     * <p><b>与 {@link #postpone(long)} 的分工</b>：顺延把**同一条任务**的截止日期往后挪，
     * 昨天做过这件事的痕迹会被这次改动覆盖掉；复制是**新开一条**，原任务原地不动
     * （通常在「已完成」里躺着），于是「这事我昨天做过」有据可查。前者适合「今天没做完」，
     * 后者适合「今天又要做一遍」—— 后者才是每天给领导发汇报邮件这种周期性事情的真实形状。</p>
     *
     * <p><b>为什么不复用 {@link #create}</b>：创建那条路会把标题 + 描述再送进
     * {@code resolveCreateGroup} 跑一遍关键词判定，而原任务的分组早就定下来了。
     * 复制品分组和执行者预期不一致（原任务是「生活」、复制品被判成「工作」），
     * 界面上看不出来，但「工作」筛选项里会悄悄多一条、少一条。这里直接沿用 {@code grp}。</p>
     *
     * <p>不搬过去的东西，逐条说明理由：</p>
     * <ul>
     *   <li><b>状态</b>：由 {@code TaskRepository.insert} 固定写 {@code 'todo'} ——
     *       复制品是「今天要做」的一件事，从「待办」起步才符合预期。</li>
     *   <li><b>顺延次数</b>：insert 不写这一列，落库默认 0。昨天顺延过 3 次是昨天的事，
     *       记在复制品头上会让它一出生就顶着一个「顺延≥3次」的红标签。</li>
     *   <li><b>番茄钟关联</b>：{@code pomodoro.task_id} 指的是原任务那一次专注，
     *       挪到复制品上等于凭空多出一条不属于它的专注记录（番茄钟面板按 task_id 聚合）。</li>
     *   <li><b>收录来源</b>：{@code source_inbox_id} 传 null。复制品的来源是「另一条任务」，
     *       不是某次收录；沿用它会让「这条收录生成了哪些任务」多算一条。</li>
     *   <li><b>{@code is_demo}</b>：**继承**原任务的。与「移至」同理（见 {@code TaskRepository.insert}
     *       的注释）：示例数据复制出来的若不带上这个标记，「清空示例数据」就清不掉它 ——
     *       用户点了清空、提示说已清空 N 条，列表里却还剩一条，只会怀疑功能坏了。</li>
     * </ul>
     *
     * <p>附件整批复制过去，磁盘上共用同一份文件（见 {@code AttachmentService.duplicateForOwner}）。
     * 复制品是独立的一行记录，所以删掉原任务不会牵连它。</p>
     */
    @Transactional(rollbackFor = Exception.class)
    public TaskVO duplicate(long id) {
        TaskRecord source = requireTask(id);
        String today = TimeUtil.format(today());
        TaskCreateRequest request = new TaskCreateRequest();
        request.setTitle(source.title);
        request.setDescription(source.description);
        request.setPriority(source.priority);
        // 截止日期一律改成今天：不管原来有没有截止、原来是不是未来。
        // 规则单一才可预测 —— 「原任务没有截止就保持没有」会让用户每次点之前都得先看一眼
        // 原任务有没有日期，才知道这次点下去会得到什么。
        request.setDue(today);
        request.setDeep(source.deep);
        request.setBlocking(source.blocking);
        request.setNote(source.note);
        request.setGrp(source.grp == null || source.grp.trim().isEmpty() ? "work" : source.grp);
        String now = now();
        long newId = taskRepository.insert(request, now, source.demo, null);
        // 附件复制落在同一个事务里：复制到一半失败要连带新任务一起回滚，
        // 否则会留下一条「任务在、附件没了」的记录 —— 而卡片上只会少几个缩略图，
        // 用户根本不会察觉是那次复制没做完。
        int copiedAttachments = attachmentService.duplicateForOwner("task", id, "task", newId);
        taskRepository.insertActivity("task",
                "复制任务「" + source.title + "」到今天 " + today
                        + (copiedAttachments > 0 ? "（含 " + copiedAttachments + " 个附件）" : ""),
                now);
        return get(newId);
    }

    @Transactional(rollbackFor = Exception.class)
    public void delete(long id) {
        TaskRecord task = requireTask(id);
        String now = now();
        taskRepository.softDelete(id, now);
        taskRepository.insertActivity("task", "删除任务「" + task.title + "」", now);
    }

    public TaskVO get(long id) {
        return toVO(requireTask(id), today());
    }

    public List<TaskVO> openTasks() {
        LocalDate today = today();
        List<TaskVO> tasks = new ArrayList<TaskVO>();
        for (TaskRecord record : taskRepository.findOpenTasks()) tasks.add(toVO(record, today));
        return tasks;
    }

    public int countAll() { return taskRepository.countAllActive(); }
    public int countDone() { return taskRepository.countDone(); }

    /**
     * 「已完成」页的按天汇总（2026-09-20）。
     *
     * <p>为什么不让前端按已加载的 100 条自己分组：任务页一次只加载 100 条，今天完成的条目
     * 完全可能排在更后面 —— 前端分组会把「今天完成 5 项」静默算成 3 项。数字一旦少了，
     * 用户只会觉得「我做完的怎么没算」，而页面上没有任何报错可查。
     * 这与「排序 / 检索必须落在服务端」是同一条理由。</p>
     *
     * <p>口径见 {@link TaskDoneSummaryVO}：只算真正完成的（{@code completed_at} 非空），
     * 取消数单独给。所有日期比较都用后端时区的 {@code today()}，
     * 前端不另算一套「今天」，避免跨零点/跨时区时两边差一天。</p>
     */
    public TaskDoneSummaryVO doneSummary() {
        LocalDate today = today();
        List<TaskRepository.DoneDayRow> rows = taskRepository.findDoneByDay();
        Map<String, TaskRepository.DoneDayRow> byDay = new LinkedHashMap<String, TaskRepository.DoneDayRow>();
        for (TaskRepository.DoneDayRow row : rows) byDay.put(row.day, row);

        String todayKey = TimeUtil.format(today);
        String yesterdayKey = TimeUtil.format(today.minusDays(1));
        // 取消数只在「今天」那一组露出来（用户要回顾的是今天的成果与放弃）。
        int canceledToday = taskRepository.countCanceledOn(todayKey);

        List<TaskDoneDayVO> days = new ArrayList<TaskDoneDayVO>();
        for (TaskRepository.DoneDayRow row : rows) {
            days.add(new TaskDoneDayVO(row.day, row.count, row.p0, row.p1, row.deep,
                    row.firstAt, row.lastAt, 0, 0));
        }

        TaskDoneDayVO todayRow = buildDay(todayKey, byDay.get(todayKey), canceledToday);
        // 「比昨天多 / 少 N 项」只在今天这一组给：其它日子给差值只是噪音，
        // 而且会诱导用户把每天的数字都拿来互相比较。
        int yesterdayCount = countOf(byDay.get(yesterdayKey));
        int diff = todayRow.getCount() - yesterdayCount;
        if (diff != 0 || yesterdayCount > 0) {
            todayRow = new TaskDoneDayVO(todayRow.getDate(), todayRow.getCount(), todayRow.getP0Count(),
                    todayRow.getP1Count(), todayRow.getDeepCount(), todayRow.getFirstAt(), todayRow.getLastAt(),
                    todayRow.getCanceledCount(), diff);
        }
        return new TaskDoneSummaryVO(todayKey, todayRow, days);
    }

    private TaskDoneDayVO buildDay(String day, TaskRepository.DoneDayRow row, int canceledCount) {
        if (row == null) return new TaskDoneDayVO(day, 0, 0, 0, 0, null, null, canceledCount, 0);
        return new TaskDoneDayVO(day, row.count, row.p0, row.p1, row.deep,
                row.firstAt, row.lastAt, canceledCount, 0);
    }

    private int countOf(TaskRepository.DoneDayRow row) { return row == null ? 0 : row.count; }

    public List<TaskVO> sortedTopTasks() {
        final LocalDate today = today();
        List<TaskVO> tasks = openTasks();
        Collections.sort(tasks, new Comparator<TaskVO>() {
            @Override
            public int compare(TaskVO left, TaskVO right) {
                int result = Integer.compare(rank(right, today), rank(left, today));
                if (result != 0) return result;
                result = Integer.compare(right.getOverdueDays(), left.getOverdueDays());
                if (result != 0) return result;
                result = Integer.compare(priorityWeight(right.getPriority()), priorityWeight(left.getPriority()));
                if (result != 0) return result;
                String leftDue = left.getDue() == null ? "9999-12-31" : left.getDue();
                String rightDue = right.getDue() == null ? "9999-12-31" : right.getDue();
                return leftDue.compareTo(rightDue);
            }
        });
        return tasks.size() <= 3 ? tasks : new ArrayList<TaskVO>(tasks.subList(0, 3));
    }

    public String reason(TaskVO task) {
        List<String> reasons = new ArrayList<String>();
        if (task.isOverdue()) reasons.add("已逾期 " + task.getOverdueDays() + " 天");
        if (task.isBlocking()) reasons.add("正在阻塞他人");
        if (task.getDue() != null) {
            LocalDate due = parseDate(task.getDue());
            if (due.equals(today())) reasons.add("今天截止");
            else if (due.equals(today().plusDays(1))) reasons.add("明天截止");
        }
        if (task.isDeep()) reasons.add("适合安排深度工作");
        if (task.getPostponed() >= 3) reasons.add("已顺延 " + task.getPostponed() + " 次");
        if (reasons.isEmpty()) reasons.add(task.getPriority() + " 优先级");
        return join(reasons, " · ");
    }

    /** 单条路径：附件按这一条查一次就够（列表路径必须走 {@code mapByOwner} 批量，见 list）。 */
    private TaskVO toVO(TaskRecord record, LocalDate today) {
        return toVO(record, today, attachmentService.listByOwner("task", record.id));
    }

    private TaskVO toVO(TaskRecord record, LocalDate today, List<AttachmentVO> attachments) {
        boolean overdue = false;
        int overdueDays = 0;
        if (record.due != null && !"done".equals(record.status) && !"canceled".equals(record.status)) {
            LocalDate due = parseDate(record.due);
            overdue = due.isBefore(today);
            if (overdue) overdueDays = (int) ChronoUnit.DAYS.between(due, today);
        }
        return new TaskVO(record.id, record.title, record.description, record.priority, record.status,
                record.due, overdue, overdueDays, record.deep, record.blocking, record.note,
                record.grp == null ? "work" : record.grp,
                record.postponed, record.sourceInboxId, record.createdAt, record.updatedAt,
                record.completedAt, record.demo,
                attachments == null ? new ArrayList<AttachmentVO>() : attachments);
    }

    private TaskRecord requireTask(long id) {
        TaskRecord task = taskRepository.findById(id);
        if (task == null) throw new BizException(ErrorCode.RESOURCE_NOT_FOUND);
        return task;
    }

    private boolean isAllowedTransition(String source, String target) {
        if (source.equals(target)) return true;
        if ("todo".equals(source)) return "doing".equals(target) || "canceled".equals(target);
        if ("doing".equals(source)) return "todo".equals(target) || "done".equals(target) || "canceled".equals(target);
        return ("done".equals(source) || "canceled".equals(source)) && "todo".equals(target);
    }

    private int rank(TaskVO task, LocalDate today) {
        if (task.isOverdue()) return 4;
        if (task.isBlocking()) return 3;
        if (task.getDue() != null) {
            LocalDate due = parseDate(task.getDue());
            if (!due.isAfter(today.plusDays(1))) return 2;
        }
        if (task.isDeep()) return 1;
        return 0;
    }

    private int priorityWeight(String priority) {
        if ("P0".equals(priority)) return 4;
        if ("P1".equals(priority)) return 3;
        if ("P2".equals(priority)) return 2;
        return 1;
    }

    private void validateOptionalStatus(String status) { if (status != null && !status.isEmpty()) validateStatus(status); }
    private void validateStatus(String status) {
        if (!("todo".equals(status) || "doing".equals(status) || "done".equals(status) || "canceled".equals(status))) {
            throw new BizException(ErrorCode.INVALID_PARAMETER, "任务状态只能填 todo / doing / done / canceled");
        }
    }
    private void validateOptionalPriority(String priority) { if (priority != null && !priority.isEmpty()) normalizePriority(priority); }
    private String normalizePriority(String priority) {
        String value = priority == null ? "P2" : priority.trim().toUpperCase();
        if (!("P0".equals(value) || "P1".equals(value) || "P2".equals(value) || "P3".equals(value))) {
            throw new BizException(ErrorCode.INVALID_PARAMETER, "优先级只能填 P0 / P1 / P2 / P3");
        }
        return value;
    }
    private void validateOptionalDate(String value) { if (value != null && !value.isEmpty()) parseDate(value); }
    private void validateOptionalGroup(String value) { if (value != null && !value.trim().isEmpty()) normalizeGroup(value); }
    /**
     * 排序口径收敛到 due / updated 两值；不传 = due（这正是前端默认那一档，不是「另一种排序」）。
     *
     * <p>报错文案必须写清合法取值：用户可能是拿接口做自动化时把 {@code updated} 拼成了
     * {@code update}，只回「请求参数不正确」他得去翻文档才知道错在哪。</p>
     */
    private String normalizeSort(String value) {
        if (value == null || value.trim().isEmpty()) return SORT_DUE;
        String sort = value.trim().toLowerCase();
        if (SORT_DUE.equals(sort) || SORT_UPDATED.equals(sort)) return sort;
        throw new BizException(ErrorCode.INVALID_PARAMETER,
                "排序方式只能填 due（按截止时间）或 updated（按最后修改时间）");
    }
    /**
     * 分组取值收敛到 work / life 两值。
     *
     * <p>报错文案必须能指导操作 —— 用户是按界面上的「工作 / 生活」点进来的，
     * 收到「请求参数不正确」只会一头雾水，所以要写清合法取值。</p>
     */
    private String normalizeGroup(String value) {
        String group = value == null ? "" : value.trim().toLowerCase();
        if (!("work".equals(group) || "life".equals(group))) {
            throw new BizException(ErrorCode.INVALID_PARAMETER, "任务分组只能填 work（工作）或 life（生活）");
        }
        return group;
    }
    /**
     * 创建时的分组落定：显式传了就用传的，否则交给 {@code FavoriteService.autoGroup} 判定。
     *
     * <p>判定用的文本是「标题 + 描述」：任务的标题常常只有两个字（「报销」「买奶粉」），
     * 只拿标题判定会漏掉写在描述里的关键线索。</p>
     */
    private String resolveCreateGroup(String requested, String title, String description) {
        if (requested != null && !requested.trim().isEmpty() && !"auto".equalsIgnoreCase(requested.trim())) {
            return normalizeGroup(requested);
        }
        return resolveGroupFor(title, description);
    }
    private String groupName(String group) { return "life".equals(group) ? "生活" : "工作"; }

    /**
     * 给「不是从任务页进来的」任务写入路径判定分组（收录确认是唯一一处）。
     *
     * <p>{@code public} 的理由与 {@code FavoriteService.autoGroup} 一样：判断规则只能有一份实现。
     * 收录确认那条路用的是裸 SQL INSERT（不走 Repository），如果它在自己那边也拼一套关键词，
     * 同一个「买奶粉」从收录进来算工作、从任务页建就算生活 —— 而这种差异界面上看不出来。</p>
     */
    public String resolveGroupFor(String title, String description) {
        String text = (title == null ? "" : title) + " " + (description == null ? "" : description);
        return normalizeGroup(favoriteService.autoGroup(text));
    }
    private String normalizeDate(String value) { return value == null || value.trim().isEmpty() ? null : TimeUtil.format(parseDate(value.trim())); }
    private LocalDate parseDate(String value) {
        try { return LocalDate.parse(value); }
        catch (DateTimeParseException exception) { throw new BizException(ErrorCode.INVALID_PARAMETER, "日期格式不正确，请按 YYYY-MM-DD 填写"); }
    }
    private String requireTitle(String title) {
        String value = title == null ? "" : title.trim();
        if (value.isEmpty() || value.length() > 200) throw new BizException(ErrorCode.INVALID_PARAMETER, "任务标题不能为空，且不能超过 200 字");
        return value;
    }
    private String trimToNull(String value) { if (value == null) return null; String trimmed = value.trim(); return trimmed.isEmpty() ? null : trimmed; }
    private String timezone() { String timezone = configRepository.findValue(AuthConstants.CONFIG_TIMEZONE); return timezone == null ? "Asia/Shanghai" : timezone; }
    private LocalDate today() { return TimeUtil.localDate(timezone()); }
    private String now() { return TimeUtil.now(timezone()); }
    private String statusName(String status) { if ("todo".equals(status)) return "待办"; if ("doing".equals(status)) return "进行中"; if ("done".equals(status)) return "已完成"; return "已取消"; }
    private String join(List<String> values, String separator) { StringBuilder result = new StringBuilder(); for (String value : values) { if (result.length() > 0) result.append(separator); result.append(value); } return result.toString(); }
}
