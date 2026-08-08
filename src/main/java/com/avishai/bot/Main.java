package com.avishai.bot;

import com.avishai.bot.core.BotFactory;
import com.avishai.bot.core.CoreBot;
import org.telegram.telegrambots.meta.TelegramBotsApi;
import org.telegram.telegrambots.meta.exceptions.TelegramApiException;
import org.telegram.telegrambots.updatesreceivers.DefaultBotSession;

public class Main {
    public static void main(String[] args) {
        String botUsername = "HomeServerManager_bot";
        String botToken = System.getenv("BOT_TOKEN");

        if (botToken == null || botToken.isEmpty()) {
            System.err.println("Could not load bot token");
            System.exit(1);
        }

        CoreBot bot = BotFactory.createBot(botUsername, botToken);
        try {
            TelegramBotsApi botsApi = new TelegramBotsApi(DefaultBotSession.class);
            botsApi.registerBot(bot);
            System.out.println("Bot is alive and listening");
        } catch (TelegramApiException e) {
            System.err.println("Critical error starting bot: " + e.getMessage());
        }
    }
}
