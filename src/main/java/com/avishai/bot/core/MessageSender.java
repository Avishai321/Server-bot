package com.avishai.bot.core;

public interface MessageSender {
    Integer sendMessage(String chatId, String text);
    void editMessage(String chatId, Integer messageId, String text);
}
