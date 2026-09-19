package com.avishai.bot.scheduler;

import com.avishai.bot.config.Config;
import com.avishai.bot.routing.MessageSender;
import lombok.extern.slf4j.Slf4j;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;


@Slf4j
public class TaskScheduler {
    private final ScheduledExecutorService executor;
    private final MessageSender bot;

    public TaskScheduler(MessageSender bot) {
        this.bot = bot;
        this.executor = Executors.newScheduledThreadPool(2);
    }

    public void schedule(BotTask task) {
        Runnable wrappedTask = () -> {
            try {
                log.info("Executing scheduled task: {}", task.getName());
                task.run();
                log.info("Completed scheduled task: {}", task.getName());
            } catch (Exception e) {
                log.error("Task '{}' crashed!", task.getName(), e);
                bot.sendMessage(
                        Config.AUTHORIZED_CHAT_ID,
                        "<b>Scheduled Task Crash</b>" +
                                "\nTask: <code>" + task.getName() + "</code>" +
                                "\nError: " + e.getMessage()
                );
            }
        };

        executor.scheduleAtFixedRate(
                wrappedTask,
                task.getInitialDelay(),
                task.getPeriod(),
                task.getTimeUnit()
        );

        log.info("Registered task '{}': next run in {} {}",
                task.getName(), task.getInitialDelay(),
                task.getTimeUnit().toString().toLowerCase()
        );
    }

    public void shutdown() {
        log.info("Shutting down TaskScheduler gracefully...");
        executor.shutdownNow();
    }
}
