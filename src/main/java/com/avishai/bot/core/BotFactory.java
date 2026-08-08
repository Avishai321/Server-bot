package com.avishai.bot.core;

import com.avishai.bot.handlers.SpotifyBackupCommandHandler;

public class BotFactory {
    public static CoreBot createBot(String botUsername, String botToken) {
        UpdateRouter router = new UpdateRouter();
        router.registerCommand(new SpotifyBackupCommandHandler());

        CoreBot bot = new CoreBot(botUsername, botToken);
        bot.setUpdateRouter(router);

        return bot;
    }
}
