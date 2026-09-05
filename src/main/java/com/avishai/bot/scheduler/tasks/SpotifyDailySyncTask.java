package com.avishai.bot.scheduler.tasks;

import com.avishai.bot.core.MessageSender;
import com.avishai.bot.scheduler.TelegramScheduledTask;
import com.avishai.bot.services.SpotifyService;

import java.time.Duration;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.concurrent.TimeUnit;

public class SpotifyDailySyncTask extends TelegramScheduledTask {
    private final SpotifyService spotifyService;

    public SpotifyDailySyncTask(SpotifyService spotifyService, MessageSender messageSender) {
        super(messageSender);
        this.spotifyService = spotifyService;
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
        if (now.compareTo(nextRun) > 0) nextRun = nextRun.plusDays(1);
        return Duration.between(now, nextRun).getSeconds();
    }

    @Override
    public long getPeriodInSeconds() {
        return TimeUnit.DAYS.toSeconds(1);
    }

    @Override
    protected void executeTask() {
        if (spotifyService.isBusy()) return;

        notifyAdmin("🔄 <b>Automated System Event</b>\nInitiating scheduled 3:00 AM Spotify Sync...");

        // 1. Send the initial UI card and grab the message ID
        Integer messageId = messageSender.sendMessage(
                adminChatId,
                """
                        🎵 <b>TASK:</b> Spotify Music Sync
                        <b>STATUS:</b> Initializing...
                        <b>Track:</b> <i>Connecting...</i>"""
        );

        // 2. Feed the UI update logic into the service so it streams live to Telegram
        if (messageId != null) spotifyService.runSync(state ->
                messageSender.editMessage(adminChatId, messageId, state.renderCard())
        );
        else spotifyService.runSync(state -> {
        });
    }
}
