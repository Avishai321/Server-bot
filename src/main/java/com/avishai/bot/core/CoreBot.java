package com.avishai.bot.core;

import org.telegram.telegrambots.bots.TelegramLongPollingBot;
import org.telegram.telegrambots.meta.api.methods.send.SendMessage;
import org.telegram.telegrambots.meta.api.objects.Update;
import org.telegram.telegrambots.meta.exceptions.TelegramApiException;

public class CoreBot extends TelegramLongPollingBot implements MessageSender {
    private final String botUsername;
    private UpdateRouter updateRouter;

    public CoreBot(String botUsername, String botToken) {
        super(botToken);
        this.botUsername = botUsername;
    }

    @Override
    public void onUpdateReceived(Update update) {
        if (this.updateRouter != null) this.updateRouter.route(update, this);
    }

    @Override
    public void sendMessage(String chatId, String text) {
        SendMessage message = new SendMessage();
        message.setChatId(chatId);
        message.setText(text);

        try {
            execute(message);
        } catch (TelegramApiException e) {
            throw new RuntimeException(e);
        }
    }

    public void setUpdateRouter(UpdateRouter updateRouter) {
        this.updateRouter = updateRouter;
    }

    @Override
    public String getBotUsername() {
        return this.botUsername;
    }
}