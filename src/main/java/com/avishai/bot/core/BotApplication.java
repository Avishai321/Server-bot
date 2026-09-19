package com.avishai.bot.core;

import com.avishai.bot.config.Config;
import com.avishai.bot.handlers.*;
import com.avishai.bot.network.NetworkManager;
import com.avishai.bot.routing.UpdateRouter;
import com.avishai.bot.scheduler.NextcloudIndexTask;
import com.avishai.bot.scheduler.SpotiSyncTask;
import com.avishai.bot.scheduler.TaskScheduler;
import com.avishai.bot.services.DockerService;
import com.avishai.bot.services.LiveLogServer;
import com.avishai.bot.services.NextcloudService;
import com.avishai.bot.services.SystemService;
import com.avishai.bot.services.spotify.*;
import lombok.extern.slf4j.Slf4j;
import org.telegram.telegrambots.meta.TelegramBotsApi;
import org.telegram.telegrambots.meta.api.methods.commands.SetMyCommands;
import org.telegram.telegrambots.meta.api.objects.commands.BotCommand;
import org.telegram.telegrambots.meta.api.objects.commands.scope.BotCommandScopeChat;
import org.telegram.telegrambots.meta.exceptions.TelegramApiException;
import org.telegram.telegrambots.updatesreceivers.DefaultBotSession;

import java.util.Comparator;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

@Slf4j
public class BotApplication {
    public static void start() throws Exception {
        validateEnvironment();

        // Core Infrastructure
        ExecutorService globalExecutor = Executors.newFixedThreadPool(Config.GLOBAL_EXECUTOR_THREADS);
        NetworkManager networkManager = new NetworkManager(globalExecutor);

        // Base Services
        NextcloudService nextcloudService = new NextcloudService();
        SystemService systemService = new SystemService();
        DockerService dockerService = new DockerService();
        SpotifyService spotifyService = new SpotifyService(
                nextcloudService,
                new SpotifyScraper(networkManager),
                new ItunesClient(networkManager),
                new LrcLibClient(networkManager),
                new MediaProcessRunner(),
                Config.SPOTIFY_DOWNLOAD_THREADS
        );
        LiveLogServer logServer = new LiveLogServer();
        logServer.start(Config.WEB_PORT);

        // Initialize Bot & Router
        CoreBot bot = new CoreBot(Config.BOT_USERNAME, Config.BOT_TOKEN);
        UpdateRouter router = new UpdateRouter();
        bot.setUpdateRouter(router);

        // Register Handlers
        List<CommandHandler> handlers = new java.util.ArrayList<>(List.of(
                new SysInfoHandler(globalExecutor, systemService),
                new SpotiSyncHandler(globalExecutor, spotifyService),
                new FolderIndexHandler(globalExecutor, nextcloudService),
                new DockerManagerHandler(globalExecutor, dockerService),
                new UpdateBotHandler(globalExecutor, systemService),
                new SysLogsHandler(globalExecutor)
        ));
        handlers.add(new HelpHandler(handlers));
        handlers.forEach(router::registerCommand);

        // Initialize Scheduling
        TaskScheduler scheduler = new TaskScheduler(bot);
        scheduler.schedule(new NextcloudIndexTask(nextcloudService, bot));
        scheduler.schedule(new SpotiSyncTask(spotifyService, bot));

        // Start API Polling
        new TelegramBotsApi(DefaultBotSession.class).registerBot(bot);
        setupNativeMenu(bot, handlers);
        bot.sendMessage(Config.AUTHORIZED_CHAT_ID,
                "<b>System Boot</b>" +
                        "\nDaemon online."
        );
        log.info("Telegram Bot API successfully registered and running.");

        // Unified Graceful Teardown
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            log.info("Initiating global shutdown sequence...");
            logServer.stop();
            scheduler.shutdown();
            spotifyService.shutdown();
            globalExecutor.shutdownNow();
        }));
    }

    private static void setupNativeMenu(CoreBot bot, List<CommandHandler> handlers) {
        List<BotCommand> commands = handlers.stream()
                .filter(h -> h.getDescription() != null && !h.getDescription().isBlank())
                .sorted(Comparator
                        .comparing(CommandHandler::getCategory)
                        .thenComparing(h -> h.getCommandSignature().get(0)))
                .map(h -> new BotCommand(
                        h.getCommandSignature().get(0),
                        h.getDescription()
                ))
                .toList();

        try {
            BotCommandScopeChat scope = new BotCommandScopeChat(Config.AUTHORIZED_CHAT_ID);
            bot.execute(new SetMyCommands(commands, scope, null));
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
