package com.avishai.bot;

import com.avishai.bot.core.BotFactory;
import com.avishai.bot.core.Config;
import com.avishai.bot.core.CoreBot;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.telegram.telegrambots.meta.TelegramBotsApi;
import org.telegram.telegrambots.meta.exceptions.TelegramApiException;
import org.telegram.telegrambots.updatesreceivers.DefaultBotSession;

public class Main {
    private static final Logger log = LoggerFactory.getLogger(Main.class);

    public static void main(String[] args) {
        log.info("Starting Home Server Manager Bot...");

        if (Config.BOT_TOKEN == null || Config.BOT_TOKEN.isEmpty()) {
            log.error("BOT_TOKEN is missing. Check your environment variables.");
            System.exit(1);
        }
        if (Config.AUTHORIZED_CHAT_ID == 0) {
            log.error("AUTHORIZED_CHAT_ID is missing or invalid. Shutting down for security.");
            System.exit(1);
        }

        try {
            CoreBot bot = BotFactory.createBot();
            TelegramBotsApi botsApi = new TelegramBotsApi(DefaultBotSession.class);
            botsApi.registerBot(bot);

            log.info("Bot successfully connected and listening for authorized commands");
        } catch (TelegramApiException e) {
            log.error("Critical error connecting to Telegram", e);
            System.exit(1);
        }
    }
}
