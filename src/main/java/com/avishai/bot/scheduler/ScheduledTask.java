package com.avishai.bot.scheduler;

public interface ScheduledTask extends Runnable {
    String getTaskName();
    long getInitialDelayInSeconds();
    long getPeriodInSeconds();
}
