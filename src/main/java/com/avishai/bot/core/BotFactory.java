package com.avishai.bot.core;

import com.avishai.bot.handlers.SpotifyBackupCommandHandler;

public class BotFactory {
    public static CoreBot createBot() {
        UpdateRouter router = new UpdateRouter();

        router.registerCommand(new SpotifyBackupCommandHandler());

        CoreBot bot = new CoreBot(Config.BOT_USERNAME, Config.BOT_TOKEN);
        bot.setUpdateRouter(router);

        return bot;
    }
}
