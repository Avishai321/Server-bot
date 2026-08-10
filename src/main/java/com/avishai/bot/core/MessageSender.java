package com.avishai.bot.core;

import org.telegram.telegrambots.meta.api.objects.replykeyboard.InlineKeyboardMarkup;

import java.io.File;

public interface MessageSender {
    Integer sendMessage(String chatId, String text, InlineKeyboardMarkup keyboard);

    Integer sendMessage(String chatId, String text);

    void editMessage(String chatId, Integer messageId, String text, InlineKeyboardMarkup keyboard);

    void editMessage(String chatId, Integer messageId, String text);

    Integer sendDocument(String chatId, String caption, File file);
}
