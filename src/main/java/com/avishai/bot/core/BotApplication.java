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

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

@Slf4j
public class BotApplication {
    public static void start() throws Exception {
        validateEnvironment();

        // Registry for all managed background services
        List<ManagedService> managedServices = new ArrayList<>();

        // Core Infrastructure
        ExecutorService globalExecutor = Executors.newFixedThreadPool(Config.GLOBAL_EXECUTOR_THREADS);
        NetworkManager networkManager = new NetworkManager(globalExecutor);

        // Base Services
        NextcloudService nextcloudService = new NextcloudService();
        SystemService systemService = new SystemService();
        DockerService dockerService = new DockerService();

        SpicetifyBridgeServer bridgeServer = new SpicetifyBridgeServer();
        bridgeServer.start();
        managedServices.add(bridgeServer);

        SpotifyService spotifyService = new SpotifyService(
                nextcloudService,
                bridgeServer,
                new ItunesClient(networkManager),
                new LrcLibClient(networkManager),
                new MediaProcessRunner(),
                Executors.newFixedThreadPool(Config.SPOTIFY_DOWNLOAD_THREADS)
        );
        managedServices.add(spotifyService);

        LiveLogServer logServer = new LiveLogServer();
        logServer.start(Config.WEB_PORT);
        managedServices.add(logServer);

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
        managedServices.add(scheduler);

        // Start API Polling
        new TelegramBotsApi(DefaultBotSession.class).registerBot(bot);
        setupNativeMenu(bot, handlers);
        bot.sendMessage(Config.AUTHORIZED_CHAT_ID,
                "<b>System Boot</b>\nDaemon online."
        );
        log.info("Telegram Bot API successfully registered and running.");

        // Unified Graceful Teardown
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            log.info("Initiating global shutdown sequence...");

            // Reverse iteration ensures dependent services (like the scheduler)
            // shut down before base services (like SpotifyService)
            for (int i = managedServices.size() - 1; i >= 0; i--) {
                try {
                    managedServices.get(i).shutdown();
                } catch (Exception e) {
                    log.error("Non-fatal error during service teardown");
                }
            }

            globalExecutor.shutdownNow();
            log.info("Daemon securely terminated.");
        }));
    }

    private static void setupNativeMenu(CoreBot bot, List<CommandHandler> handlers) {
        Class<com.avishai.bot.routing.BotCommand> botAnn = com.avishai.bot.routing.BotCommand.class;

        List<BotCommand> commands = handlers.stream()
                .filter(h -> h.getClass().isAnnotationPresent(botAnn))
                .map(h -> h.getClass().getAnnotation(botAnn))
                .filter(ann -> !ann.description().isBlank())
                .sorted(Comparator.comparing(com.avishai.bot.routing.BotCommand::category)
                        .thenComparing(ann -> ann.command()[0]))
                .map(ann -> new BotCommand(
                        ann.command()[0],
                        ann.description()
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
