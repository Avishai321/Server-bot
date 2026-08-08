package com.avishai.bot.core;

public interface MessageSender {
    void sendMessage(String chatId, String text);
}
