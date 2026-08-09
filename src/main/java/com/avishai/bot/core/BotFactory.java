package com.avishai.bot.core;

import com.avishai.bot.handlers.SpotiSyncCommandHandler;

public class BotFactory {
    public static CoreBot createBot() {
        UpdateRouter router = new UpdateRouter();

        router.registerCommand(new SpotiSyncCommandHandler());

        CoreBot bot = new CoreBot(Config.BOT_USERNAME, Config.BOT_TOKEN);
        bot.setUpdateRouter(router);

        return bot;
    }
}
