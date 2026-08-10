package com.avishai.bot.core;

import lombok.Setter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.telegram.telegrambots.bots.TelegramLongPollingBot;
import org.telegram.telegrambots.meta.api.methods.send.SendDocument;
import org.telegram.telegrambots.meta.api.methods.send.SendMessage;
import org.telegram.telegrambots.meta.api.methods.updatingmessages.EditMessageText;
import org.telegram.telegrambots.meta.api.objects.InputFile;
import org.telegram.telegrambots.meta.api.objects.Message;
import org.telegram.telegrambots.meta.api.objects.Update;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.InlineKeyboardMarkup;
import org.telegram.telegrambots.meta.exceptions.TelegramApiException;

import java.io.File;

public class CoreBot extends TelegramLongPollingBot implements MessageSender {
    private static final Logger log = LoggerFactory.getLogger(CoreBot.class);
    private final String botUsername;
    @Setter
    private UpdateRouter updateRouter;

    public CoreBot(String botUsername, String botToken) {
        super(botToken);
        this.botUsername = botUsername;
    }

    @Override
    public String getBotUsername() {
        return this.botUsername;
    }

    @Override
    public void onUpdateReceived(Update update) {
        if (this.updateRouter != null) this.updateRouter.route(update, this);
    }

    @Override
    public Integer sendMessage(String chatId, String text, InlineKeyboardMarkup keyboard) {
        SendMessage message = new SendMessage();
        message.setChatId(chatId);
        message.setText(text);
        message.setParseMode("HTML");
        if (keyboard != null) message.setReplyMarkup(keyboard);
        try {
            Message sentMessage = execute(message);
            return sentMessage.getMessageId();
        } catch (TelegramApiException e) {
            log.error("Failed to send message to {}", chatId, e);
            return null;
        }
    }

    @Override
    public Integer sendMessage(String chatId, String text) {
        return sendMessage(chatId, text, null);
    }

    @Override
    public void editMessage(String chatId, Integer messageId, String text, InlineKeyboardMarkup keyboard) {
        if (messageId == null) return;

        EditMessageText editMessage = new EditMessageText();
        editMessage.setChatId(chatId);
        editMessage.setMessageId(messageId);
        editMessage.setText(text);
        editMessage.setParseMode("HTML");
        if (keyboard != null) editMessage.setReplyMarkup(keyboard);
        try {
            execute(editMessage);
        } catch (TelegramApiException e) {
            if (!e.getMessage().contains("message is not modified")) {
                log.debug("Could not edit message {}: {}", messageId, e.getMessage());
            }
        }
    }

    @Override
    public void editMessage(String chatId, Integer messageId, String text) {
        editMessage(chatId, messageId, text, null);
    }

    @Override
    public Integer sendDocument(String chatId, String caption, File file) {
        SendDocument document = new SendDocument();
        document.setChatId(chatId);
        document.setDocument(new InputFile(file));

        if (caption != null) {
            document.setCaption(caption);
            document.setParseMode("HTML");
        }

        try {
            Message sentMessage = execute(document);
            return sentMessage.getMessageId();
        } catch (TelegramApiException e) {
            log.error("Failed to send document to {}", chatId, e);
            return null;
        }
    }
}
