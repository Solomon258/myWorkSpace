package com.icecode.workbench.dashboard;

import java.util.List;

import com.icecode.workbench.schedule.EventVO;

public class DashboardTodayVO {
    private final String date;
    private final DashboardMetricsVO metrics;
    private final String brief;
    private final List<DashboardTaskVO> topTasks;
    private final List<DashboardTaskVO> todayTasks;
    private final List<EventVO> events;

    public DashboardTodayVO(String date, DashboardMetricsVO metrics, String brief,
                            List<DashboardTaskVO> topTasks, List<DashboardTaskVO> todayTasks,
                            List<EventVO> events) {
        this.date = date;
        this.metrics = metrics;
        this.brief = brief;
        this.topTasks = topTasks;
        this.todayTasks = todayTasks;
        this.events = events;
    }

    public String getDate() { return date; }
    public DashboardMetricsVO getMetrics() { return metrics; }
    public String getBrief() { return brief; }
    public List<DashboardTaskVO> getTopTasks() { return topTasks; }
    public List<DashboardTaskVO> getTodayTasks() { return todayTasks; }
    public List<EventVO> getEvents() { return events; }
}
