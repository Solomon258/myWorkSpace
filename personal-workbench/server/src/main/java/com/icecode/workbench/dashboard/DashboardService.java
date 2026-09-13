package com.icecode.workbench.dashboard;

import java.util.ArrayList;
import java.util.List;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.icecode.workbench.auth.AppConfigRepository;
import com.icecode.workbench.auth.AuthConstants;
import com.icecode.workbench.inbox.InboxRepository;
import com.icecode.workbench.pomodoro.PomodoroService;
import com.icecode.workbench.schedule.EventService;
import com.icecode.workbench.task.TaskService;
import com.icecode.workbench.task.TaskVO;
import com.icecode.workbench.util.TimeUtil;

@Service
public class DashboardService {

    private final TaskService taskService;
    private final EventService eventService;
    private final AppConfigRepository configRepository;
    private final InboxRepository inboxRepository;
    private final PomodoroService pomodoroService;
    private final DailyPlanRepository dailyPlanRepository;

    public DashboardService(TaskService taskService, EventService eventService,
                            AppConfigRepository configRepository, InboxRepository inboxRepository,
                            PomodoroService pomodoroService, DailyPlanRepository dailyPlanRepository) {
        this.taskService = taskService;
        this.eventService = eventService;
        this.configRepository = configRepository;
        this.inboxRepository = inboxRepository;
        this.pomodoroService = pomodoroService;
        this.dailyPlanRepository = dailyPlanRepository;
    }

    public DashboardTodayVO today() {
        String timezone = timezone();
        String today = TimeUtil.today(timezone);
        List<TaskVO> openTasks = taskService.openTasks();
        int overdue = 0;
        List<DashboardTaskVO> todayTasks = new ArrayList<DashboardTaskVO>();
        for (TaskVO task : openTasks) {
            if (task.isOverdue()) overdue++;
            if (today.equals(task.getDue())) todayTasks.add(toDashboardTask(task));
        }
        List<DashboardTaskVO> topTasks = new ArrayList<DashboardTaskVO>();
        for (TaskVO task : taskService.sortedTopTasks()) topTasks.add(toDashboardTask(task));
        int total = taskService.countAll();
        int done = taskService.countDone();
        int open = openTasks.size();
        int eventCount = eventService.countToday();
        int inboxPending = inboxRepository.countPending();
        int pomoToday = pomodoroService.today().getCount();
        DashboardMetricsVO metrics = new DashboardMetricsVO(done, total, open, overdue, eventCount,
                inboxPending, pomoToday);
        String theme = dailyPlanRepository.findTheme(today);
        String brief = buildBrief(overdue, inboxPending);
        return new DashboardTodayVO(today, theme, metrics, brief, topTasks, todayTasks,
                eventService.todayEvents());
    }

    @Transactional(rollbackFor = Exception.class)
    public String saveTheme(String theme) {
        String timezone = timezone();
        String today = TimeUtil.today(timezone);
        String now = TimeUtil.now(timezone);
        String trimmed = theme.trim();
        dailyPlanRepository.saveTheme(today, trimmed, now);
        dailyPlanRepository.insertActivity("plan", "设定今日主题：" + trimmed, now);
        return trimmed;
    }

    private String buildBrief(int overdue, int inboxPending) {
        StringBuilder brief = new StringBuilder();
        if (overdue > 0) {
            brief.append("当前有 ").append(overdue).append(" 个逾期任务，建议先处理 Top3 中的红色事项。");
        } else {
            brief.append("当前没有逾期任务，按 Top3 顺序推进即可。");
        }
        if (inboxPending > 0) {
            brief.append("收集箱还有 ").append(inboxPending).append(" 条待整理，清空它再开始深度工作。");
        }
        return brief.toString();
    }

    private String timezone() {
        String timezone = configRepository.findValue(AuthConstants.CONFIG_TIMEZONE);
        return timezone == null ? "Asia/Shanghai" : timezone;
    }

    private DashboardTaskVO toDashboardTask(TaskVO task) {
        return new DashboardTaskVO(task.getId(), task.getTitle(), task.getPriority(), task.getStatus(),
                task.getDue(), task.isOverdue(), task.getOverdueDays(), task.isDeep(), task.isBlocking(),
                task.getPostponed(), taskService.reason(task));
    }
}
