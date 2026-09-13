package com.icecode.workbench.pomodoro;

public class PomoConfigVO {
    private final int work;
    private final int shortBreak;
    private final int longBreak;
    private final boolean auto;

    public PomoConfigVO(int work, int shortBreak, int longBreak, boolean auto) {
        this.work = work;
        this.shortBreak = shortBreak;
        this.longBreak = longBreak;
        this.auto = auto;
    }

    public int getWork() { return work; }
    public int getShortBreak() { return shortBreak; }
    public int getLongBreak() { return longBreak; }
    public boolean isAuto() { return auto; }
}
