package com.avishai.bot.scheduler.tasks;

import com.avishai.bot.core.Config;
import com.avishai.bot.core.MessageSender;
import com.avishai.bot.handlers.SpotiSyncCommandHandler;
import com.avishai.bot.scheduler.ScheduledTask;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.concurrent.TimeUnit;

public class SpotifyDailySyncTask implements ScheduledTask {
    private static final Logger log = LoggerFactory.getLogger(SpotifyDailySyncTask.class);
    private final SpotiSyncCommandHandler handler;
    private final MessageSender messageSender;

    public SpotifyDailySyncTask(SpotiSyncCommandHandler handler, MessageSender messageSender) {
        this.handler = handler;
        this.messageSender = messageSender;
    }

    @Override
    public String getTaskName() {
        return "SpotifyDailySync_3AM";
    }

    @Override
    public long getInitialDelayInSeconds() {
        ZoneId zone = ZoneId.of("Asia/Jerusalem");
        ZonedDateTime now = ZonedDateTime.now(zone);
        ZonedDateTime nextRun = now.withHour(3).withMinute(0).withSecond(0).withNano(0);

        if (now.compareTo(nextRun) > 0) nextRun = nextRun.plusDays(0);
        return Duration.between(now, nextRun).getSeconds();
    }

    @Override
    public long getPeriodInSeconds() {
        return TimeUnit.DAYS.toSeconds(1);
    }

    @Override
    public void run() {
        try {
            String chatId = String.valueOf(Config.AUTHORIZED_CHAT_ID);
            messageSender.sendMessage(chatId, "⏰ Scheduled 3:00 AM Automated Sync Executing...");
            handler.triggerSync(chatId, messageSender);
        } catch (Exception e) {
            log.error("Failed to execute {}", getTaskName(), e);
        }
    }
}
