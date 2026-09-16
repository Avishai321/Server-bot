package com.avishai.bot.scheduler;

import com.avishai.bot.config.BotCommands;
import com.avishai.bot.config.Config;
import com.avishai.bot.routing.MessageSender;
import com.avishai.bot.services.spotify.SpotifyService;
import com.avishai.bot.util.TelegramUi;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import java.util.concurrent.TimeUnit;

@Slf4j
@RequiredArgsConstructor
public class SpotiSyncTask implements BotTask {
    private final SpotifyService spotifyService;
    private final MessageSender bot;

    @Override
    public String getName() {
        return "SpotiSync Daily";
    }

    @Override
    public long getInitialDelay() {
        return calculateDelayToHour(3);
    }

    @Override
    public long getPeriod() {
        return TimeUnit.DAYS.toSeconds(1);
    }

    @Override
    public TimeUnit getTimeUnit() {
        return TimeUnit.SECONDS;
    }

    @Override
    public void run() {
        if (spotifyService.isBusy()) {
            log.warn("SpotiSyncTask skipped: Service is already executing a sync.");
            return;
        }

        Integer msgId = bot.sendMessage(
                Config.AUTHORIZED_CHAT_ID_STR,
                "<b>Automated System Event</b>\nInitiating scheduled Spotisync...",
                TelegramUi.singleButtonKeyboard("Abort", BotCommands.STOP_SPOTIFY_BACKUP)
        );

        spotifyService.runSync(state -> {
            var keyboard = state.isActive()
                    ? TelegramUi.singleButtonKeyboard("Abort", BotCommands.STOP_SPOTIFY_BACKUP)
                    : null;

            if (msgId != null) bot.editMessage(
                    Config.AUTHORIZED_CHAT_ID_STR,
                    msgId,
                    state.renderCard(),
                    keyboard
            );
        });
    }
}
