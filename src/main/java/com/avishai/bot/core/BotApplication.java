package com.avishai.bot.core;

import com.avishai.bot.config.BotCommands;
import com.avishai.bot.config.Config;
import com.avishai.bot.handlers.*;
import com.avishai.bot.scheduler.TaskScheduler;
import com.avishai.bot.scheduler.tasks.SpotifyDailySyncTask;
import com.avishai.bot.services.DockerService;
import com.avishai.bot.services.NextcloudService;
import com.avishai.bot.services.SpotifyService;
import com.avishai.bot.services.SystemService;
import lombok.extern.slf4j.Slf4j;
import org.telegram.telegrambots.meta.TelegramBotsApi;
import org.telegram.telegrambots.meta.api.methods.commands.SetMyCommands;
import org.telegram.telegrambots.meta.api.objects.commands.BotCommand;
import org.telegram.telegrambots.meta.api.objects.commands.scope.BotCommandScopeDefault;
import org.telegram.telegrambots.meta.exceptions.TelegramApiException;
import org.telegram.telegrambots.updatesreceivers.DefaultBotSession;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

@Slf4j
public class BotApplication {
    public static void start() throws Exception {
        validateEnvironment();

        UpdateRouter router = new UpdateRouter();
        CoreBot bot = new CoreBot(Config.BOT_USERNAME, Config.BOT_TOKEN);
        bot.setUpdateRouter(router);

        // Thread Pools
        ExecutorService globalExecutor = Executors.newCachedThreadPool();

        // Initialize Services
        SpotifyService spotifyService = new SpotifyService();
        DockerService dockerService = new DockerService();
        SystemService systemService = new SystemService();
        NextcloudService nextcloudService = new NextcloudService();

        // Initialize Handlers (Controllers)
        List<CommandHandler> handlers = List.of(
                new SpotiSyncHandler(globalExecutor, spotifyService),
                new UpdateBotHandler(globalExecutor, systemService),
                new DockerManagerHandler(globalExecutor, dockerService),
                new SysInfoHandler(globalExecutor, systemService),
                new FolderIndexHandler(globalExecutor, nextcloudService),
                new HelpHandler()
        );
        handlers.forEach(router::registerCommand);

        // Initialize Scheduler
        TaskScheduler scheduler = new TaskScheduler();
        scheduler.registerTask(new SpotifyDailySyncTask(spotifyService, bot));

        TelegramBotsApi botsApi = new TelegramBotsApi(DefaultBotSession.class);
        botsApi.registerBot(bot);
        log.info("Telegram Bot API successfully registered.");

        setupNativeMenu(bot);
        scheduler.startAll();

        bot.sendMessage(String.valueOf(Config.AUTHORIZED_CHAT_ID),
                "🚀 <b>System Boot</b>\nHome Server Manager Daemon is online and ready."
        );
    }

    private static void setupNativeMenu(CoreBot bot) {
        List<BotCommand> commands = new ArrayList<>();
        commands.add(new BotCommand(BotCommands.HELP, "Show control menu"));
        commands.add(new BotCommand(BotCommands.SYS_INFO, "System hardware health"));
        commands.add(new BotCommand(BotCommands.DOCKER_MANAGER, "Manage Docker containers"));
        commands.add(new BotCommand(BotCommands.INDEX_FOLDER, "Index specific folder"));
        commands.add(new BotCommand(BotCommands.SPOTIFY_BACKUP, "Sync Spotify to Nextcloud"));
        commands.add(new BotCommand(BotCommands.UPDATE_BOT, "Recompile and restart bot"));

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
