package com.avishai.bot.core;

import com.avishai.bot.handlers.SpotiSyncCommandHandler;
import com.avishai.bot.scheduler.TaskScheduler;
import com.avishai.bot.scheduler.tasks.SpotifyDailySyncTask;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.telegram.telegrambots.meta.TelegramBotsApi;
import org.telegram.telegrambots.updatesreceivers.DefaultBotSession;

public class BotApplication {
    private static final Logger log = LoggerFactory.getLogger(BotApplication.class);

    public static void start() throws Exception {
        validateEnvironment();

        UpdateRouter router = new UpdateRouter();
        CoreBot bot = new CoreBot(Config.BOT_USERNAME, Config.BOT_TOKEN);
        bot.setUpdateRouter(router);

        SpotiSyncCommandHandler spotiSyncHandler = new SpotiSyncCommandHandler();
        router.registerCommand(spotiSyncHandler);

        TaskScheduler scheduler = new TaskScheduler();
        scheduler.registerTask(new SpotifyDailySyncTask(spotiSyncHandler, bot));

        TelegramBotsApi botsApi = new TelegramBotsApi(DefaultBotSession.class);
        botsApi.registerBot(bot);
        log.info("Telegram Bot API successfully registered.");

        scheduler.startAll();
        log.info("Scheduler framework successfully started.");
    }

    private static void validateEnvironment() {
        if (Config.BOT_TOKEN == null || Config.BOT_TOKEN.isEmpty()) {
            throw new IllegalArgumentException("BOT_TOKEN is missing. Check your environment variables.");
        }
        if (Config.AUTHORIZED_CHAT_ID == 0) {
            throw new IllegalArgumentException("AUTHORIZED_CHAT_ID is missing or invalid.");
        }
    }
}
