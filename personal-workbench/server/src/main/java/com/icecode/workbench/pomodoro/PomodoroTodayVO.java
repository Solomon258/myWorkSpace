package com.icecode.workbench.pomodoro;

public class PomodoroTodayVO {
    private final int count;
    private final int minutes;

    public PomodoroTodayVO(int count, int minutes) {
        this.count = count;
        this.minutes = minutes;
    }

    public int getCount() { return count; }
    public int getMinutes() { return minutes; }
}
