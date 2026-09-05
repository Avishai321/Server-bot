package com.avishai.bot.core;

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
import java.util.Comparator;
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

        ExecutorService globalExecutor = Executors.newCachedThreadPool();

        SpotifyService spotifyService = new SpotifyService();

        List<CommandHandler> handlers = buildHandlers(
                globalExecutor,
                new SystemService(),
                spotifyService,
                new NextcloudService(),
                new DockerService()
        );
        handlers.forEach(router::registerCommand);

        TaskScheduler scheduler = new TaskScheduler();
        scheduler.registerTask(new SpotifyDailySyncTask(spotifyService, bot));

        TelegramBotsApi botsApi = new TelegramBotsApi(DefaultBotSession.class);
        botsApi.registerBot(bot);
        log.info("Telegram Bot API successfully registered.");

        setupNativeMenu(bot, handlers);
        scheduler.startAll();

        bot.sendMessage(String.valueOf(Config.AUTHORIZED_CHAT_ID),
                "🚀 <b>System Boot</b>\nHome Server Manager Daemon is online and ready."
        );
    }

    private static List<CommandHandler> buildHandlers(
            ExecutorService executor,
            SystemService systemService,
            SpotifyService spotifyService,
            NextcloudService nextcloudService,
            DockerService dockerService
    ) {
        List<CommandHandler> handlers = new ArrayList<>(List.of(
                new SysInfoHandler(executor, systemService),
                new SpotiSyncHandler(executor, spotifyService),
                new FolderIndexHandler(executor, nextcloudService),
                new DockerManagerHandler(executor, dockerService),
                new UpdateBotHandler(executor, systemService)
        ));

        handlers.addFirst(new HelpHandler(handlers));
        return handlers;
    }

    private static void setupNativeMenu(CoreBot bot, List<CommandHandler> handlers) {
        List<BotCommand> commands = handlers.stream()
                .filter(h -> !h.getDescription().isEmpty())
                // Sort by HandlerCategory enum order to guarantee predictability
                .sorted(Comparator.comparing(CommandHandler::getCategory))
                .map(h -> new BotCommand(
                        h.getCommandSignature().get(0),
                        h.getDescription()
                ))
                .toList();

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
