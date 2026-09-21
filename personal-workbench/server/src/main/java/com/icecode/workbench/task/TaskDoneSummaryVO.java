package com.icecode.workbench.task;

import java.util.List;

/**
 * 「已完成」页的按天汇总（2026-09-20）。
 *
 * <p>为什么汇总要由后端算、而不是前端按已加载的 100 条分组：
 * 任务页一次只加载 100 条（{@code TaskService.list} 的 size 上限），今天完成的条目
 * 完全可能排在这 100 条之外 —— 前端分组会把它静默少算，而「今天完成了 5 项」这种数字
 * 一旦少了，用户看到的是「我明明做完的怎么没算」，却找不到任何报错。
 * 这与「排序 / 检索必须落在服务端」是同一个理由（见 {@code TaskRepository.find} 的注释）。</p>
 *
 * <p>口径（2026-09-20 与用户确认）：<b>「今天完成」只算真正完成的（{@code status='done'}）</b>，
 * 依据 {@code task.completed_at}。已取消的任务 {@code completed_at} 是 NULL
 * （见 {@code TaskService.changeStatus}：只有 done 才写入），所以它天然不混进主数字；
 * 取消数单独走 {@code canceledToday} 给出，界面上另行标注、不并入「完成 N 项」。</p>
 */
public class TaskDoneSummaryVO {

    private final String date;
    private final TaskDoneDayVO today;
    private final List<TaskDoneDayVO> days;

    public TaskDoneSummaryVO(String date, TaskDoneDayVO today, List<TaskDoneDayVO> days) {
        this.date = date;
        this.today = today;
        this.days = days;
    }

    /** 后端认定的「今天」（按用户时区算），前端分组与相对日期文案都读它。 */
    public String getDate() { return date; }
    /** 今天那一组的完整汇总（含最早 / 最晚完成时间、与昨天的差值）。 */
    public TaskDoneDayVO getToday() { return today; }
    /** 每天一组的计数，按完成日期倒序；只含真正完成的（done）。 */
    public List<TaskDoneDayVO> getDays() { return days; }
}
