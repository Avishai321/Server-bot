package com.avishai.bot.core;

import com.avishai.bot.handlers.commands.*;
import com.avishai.bot.scheduler.TaskScheduler;
import com.avishai.bot.scheduler.tasks.SpotifyDailySyncTask;
import com.avishai.bot.utils.BotCommands;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.telegram.telegrambots.meta.TelegramBotsApi;
import org.telegram.telegrambots.meta.api.methods.commands.SetMyCommands;
import org.telegram.telegrambots.meta.api.objects.commands.BotCommand;
import org.telegram.telegrambots.meta.api.objects.commands.scope.BotCommandScopeDefault;
import org.telegram.telegrambots.meta.exceptions.TelegramApiException;
import org.telegram.telegrambots.updatesreceivers.DefaultBotSession;

import java.util.ArrayList;
import java.util.List;

public class BotApplication {
    private static final Logger log = LoggerFactory.getLogger(BotApplication.class);

    public static void start() throws Exception {
        validateEnvironment();

        UpdateRouter router = new UpdateRouter();
        CoreBot bot = new CoreBot(Config.BOT_USERNAME, Config.BOT_TOKEN);
        bot.setUpdateRouter(router);

        SpotiSyncHandler spotiSyncHandler = new SpotiSyncHandler();
        List<CommandHandler> handlers = List.of(
                spotiSyncHandler,
                new UpdateBotHandler(),
                new DockerManagerHandler(),
                new HelpHandler()
        );
        handlers.forEach(router::registerCommand);

        TaskScheduler scheduler = new TaskScheduler();
        scheduler.registerTask(new SpotifyDailySyncTask(spotiSyncHandler, bot));

        TelegramBotsApi botsApi = new TelegramBotsApi(DefaultBotSession.class);
        botsApi.registerBot(bot);
        log.info("Telegram Bot API successfully registered.");

        setupNativeMenu(bot);

        scheduler.startAll();
        log.info("Scheduler framework successfully started.");
    }

    private static void setupNativeMenu(CoreBot bot) {
        List<BotCommand> commands = new ArrayList<>();
        commands.add(new BotCommand(BotCommands.SPOTIFY_BACKUP, "Sync Spotify to Nextcloud"));
        commands.add(new BotCommand(BotCommands.DOCKER_MANAGER, "Manage Docker containers"));
        commands.add(new BotCommand(BotCommands.UPDATE_BOT, "Recompile and restart bot"));
        commands.add(new BotCommand(BotCommands.HELP, "Show control menu"));

        try {
            bot.execute(new SetMyCommands(commands, new BotCommandScopeDefault(), null));
        } catch (TelegramApiException e) {
            log.warn("Failed to set native bot commands", e);
        }
    }

    private static void validateEnvironment() {
        if (Config.BOT_TOKEN == null || Config.BOT_TOKEN.isEmpty()) {
            throw new IllegalArgumentException("BOT_TOKEN is missing.");
        }
    }
}
