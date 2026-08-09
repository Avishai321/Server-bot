package com.avishai.bot.scheduler;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

public class TaskScheduler {
    private static final Logger log = LoggerFactory.getLogger(TaskScheduler.class);
    private final ScheduledExecutorService executorService;
    private final List<ScheduledTask> tasks;

    public TaskScheduler() {
        this.executorService = Executors.newScheduledThreadPool(2);
        this.tasks = new ArrayList<>();
    }

    public void registerTask(ScheduledTask task) {
        tasks.add(task);
        log.info("Registered scheduled task: {}", task.getTaskName());
    }

    public void startAll() {
        for (ScheduledTask task : tasks) {
            long initialDelay = task.getInitialDelayInSeconds();
            long period = task.getPeriodInSeconds();

            executorService.scheduleAtFixedRate(
                    task,
                    initialDelay,
                    period,
                    TimeUnit.SECONDS
            );

            log.info("Task '{}' scheduled to start in {} seconds, repeating every {} seconds.",
                    task.getTaskName(), initialDelay, period);
        }
    }
}
