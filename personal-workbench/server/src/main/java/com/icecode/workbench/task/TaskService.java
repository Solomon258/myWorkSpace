package com.icecode.workbench.task;

import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.icecode.workbench.auth.AppConfigRepository;
import com.icecode.workbench.auth.AuthConstants;
import com.icecode.workbench.common.BizException;
import com.icecode.workbench.common.ErrorCode;
import com.icecode.workbench.util.TimeUtil;

@Service
public class TaskService {

    private static final String[] PRAISES = {
            "干得漂亮，又拿下一城！", "稳！这个节奏保持住。", "漂亮，这事终于落地了。",
            "执行力拉满，向你致敬。", "状态正佳，乘胜追击！", "积小胜为大胜，很好。"
    };

    private final TaskRepository taskRepository;
    private final AppConfigRepository configRepository;

    public TaskService(TaskRepository taskRepository, AppConfigRepository configRepository) {
        this.taskRepository = taskRepository;
        this.configRepository = configRepository;
    }

    public List<TaskVO> list(String status, String priority, String keyword, String dueFrom,
                             String dueTo, int page, int size) {
        validateOptionalStatus(status);
        validateOptionalPriority(priority);
        validateOptionalDate(dueFrom);
        validateOptionalDate(dueTo);
        int safePage = Math.max(1, page);
        int safeSize = Math.min(100, Math.max(1, size));
        LocalDate today = today();
        List<TaskVO> result = new ArrayList<TaskVO>();
        for (TaskRecord record : taskRepository.find(status, priority, keyword, dueFrom, dueTo, safePage, safeSize)) {
            result.add(toVO(record, today));
        }
        return result;
    }

    @Transactional(rollbackFor = Exception.class)
    public TaskVO create(TaskCreateRequest request) {
        request.setTitle(requireTitle(request.getTitle()));
        request.setPriority(normalizePriority(request.getPriority()));
        request.setDue(normalizeDate(request.getDue()));
        String now = now();
        long id = taskRepository.insert(request, now);
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
        if (request.getNote() != null) task.note = trimToNull(request.getNote());
        task.updatedAt = now();
        taskRepository.update(task);
        taskRepository.insertActivity("task", "编辑任务「" + task.title + "」", task.updatedAt);
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

    private TaskVO toVO(TaskRecord record, LocalDate today) {
        boolean overdue = false;
        int overdueDays = 0;
        if (record.due != null && !"done".equals(record.status) && !"canceled".equals(record.status)) {
            LocalDate due = parseDate(record.due);
            overdue = due.isBefore(today);
            if (overdue) overdueDays = (int) ChronoUnit.DAYS.between(due, today);
        }
        return new TaskVO(record.id, record.title, record.description, record.priority, record.status,
                record.due, overdue, overdueDays, record.deep, record.blocking, record.note,
                record.postponed, record.sourceInboxId, record.createdAt, record.updatedAt,
                record.completedAt, record.demo);
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
