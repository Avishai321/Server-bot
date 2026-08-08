package com.avishai.bot.handlers;

import com.avishai.bot.core.MessageSender;
import org.telegram.telegrambots.meta.api.objects.Update;

public interface CommandHandler {
    String getCommandSignature();
    void handle(Update update, MessageSender messageSender);
}
