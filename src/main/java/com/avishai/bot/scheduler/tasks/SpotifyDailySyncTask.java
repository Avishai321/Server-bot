package com.avishai.bot.scheduler.tasks;

import com.avishai.bot.core.CommandContext;
import com.avishai.bot.core.MessageSender;
import com.avishai.bot.handlers.SpotiSyncHandler;
import com.avishai.bot.scheduler.TelegramScheduledTask;
import com.avishai.bot.config.BotCommands;

import java.time.Duration;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.concurrent.TimeUnit;

public class SpotifyDailySyncTask extends TelegramScheduledTask {
    private final SpotiSyncHandler handler;

    public SpotifyDailySyncTask(SpotiSyncHandler handler, MessageSender messageSender) {
        super(messageSender);
        this.handler = handler;
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

        if (now.compareTo(nextRun) > 0) {
            nextRun = nextRun.plusDays(1);
        }
        return Duration.between(now, nextRun).getSeconds();
    }

    @Override
    public long getPeriodInSeconds() {
        return TimeUnit.DAYS.toSeconds(1);
    }

    @Override
    protected void executeTask() {
        notifyAdmin("⏰ <b>Automated System Event</b>\nInitiating scheduled 3:00 AM Spotify Sync...");
        CommandContext ctx = createSystemContext(BotCommands.SPOTIFY_BACKUP);
        handler.triggerSync(ctx);
    }
}
