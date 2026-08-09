package com.avishai.bot.core;

import org.telegram.telegrambots.meta.api.objects.replykeyboard.InlineKeyboardMarkup;

public interface MessageSender {
    Integer sendMessage(String chatId, String text, InlineKeyboardMarkup keyboard);
    Integer sendMessage(String chatId, String text);

    void editMessage(String chatId, Integer messageId, String text, InlineKeyboardMarkup keyboard);
    void editMessage(String chatId, Integer messageId, String text);
}
