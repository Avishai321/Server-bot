package com.avishai.bot.core;

import com.avishai.bot.handlers.CommandHandler;
import com.avishai.bot.utils.BotMessages;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.telegram.telegrambots.meta.api.objects.Update;

import java.util.HashMap;
import java.util.Map;

public class UpdateRouter {
    private static final Logger log = LoggerFactory.getLogger(UpdateRouter.class);
    private final Map<String, CommandHandler> commandRegistry;

    public UpdateRouter() {
        this.commandRegistry = new HashMap<>();
    }

    public void registerCommand(CommandHandler handler) {
        commandRegistry.put(handler.getCommandSignature(), handler);
    }

    public void route(Update update, MessageSender messageSender) {
        if (!update.hasMessage() || !update.getMessage().hasText()) return;

        String chatId = update.getMessage().getChatId().toString();
        if (update.getMessage().getText().startsWith("/")) {
            String commandSignature = update.getMessage().getText().split(" ")[0];
            CommandHandler handler = commandRegistry.get(commandSignature);
            if (handler != null) handler.handle(update, messageSender);
            else handleUnknownCommand(chatId, messageSender);
        }
    }

    public void handleUnknownCommand(String chatId, MessageSender messageSender) {
        messageSender.sendMessage(chatId, BotMessages.UNKNOWN_COMMAND);
    }
}
