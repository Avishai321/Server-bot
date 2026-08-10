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

        // Ensure background threads stop cleanly when the bot restarts or updates
        Runtime.getRuntime().addShutdownHook(new Thread(this::shutdown));
    }

    public void registerTask(ScheduledTask task) {
        tasks.add(task);
        log.info("Registered scheduled task: {}", task.getTaskName());
    }

    public void startAll() {
        for (ScheduledTask task : tasks) {
            long initialDelay = task.getInitialDelayInSeconds();
            long period = task.getPeriodInSeconds();
            executorService.scheduleAtFixedRate(task, initialDelay, period, TimeUnit.SECONDS);
            log.info("Task '{}' scheduled. Next run in {}s, interval: {}s", task.getTaskName(), initialDelay, period);
        }
    }

    private void shutdown() {
        log.info("Shutting down TaskScheduler gracefully...");
        executorService.shutdown();
        try {
            if (!executorService.awaitTermination(5, TimeUnit.SECONDS)) {
                executorService.shutdownNow();
            }
        } catch (InterruptedException e) {
            executorService.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }
}
