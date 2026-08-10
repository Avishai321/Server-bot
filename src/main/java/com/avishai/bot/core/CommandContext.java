package com.avishai.bot.core;

import org.telegram.telegrambots.meta.api.objects.Update;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.InlineKeyboardMarkup;

import java.io.File;

public record CommandContext(String command, Update update, String chatId, MessageSender messageSender) {

    public Integer reply(String text) {
        return messageSender.sendMessage(this.chatId, text);
    }

    public Integer reply(String text, InlineKeyboardMarkup keyboard) {
        return messageSender.sendMessage(this.chatId, text, keyboard);
    }

    public void edit(Integer messageId, String text) {
        messageSender.editMessage(this.chatId, messageId, text);
    }

    public void edit(Integer messageId, String text, InlineKeyboardMarkup keyboard) {
        messageSender.editMessage(this.chatId, messageId, text, keyboard);
    }

    public Integer sendDocument(String caption, File file) {
        return messageSender.sendDocument(this.chatId, caption, file);
    }
}
