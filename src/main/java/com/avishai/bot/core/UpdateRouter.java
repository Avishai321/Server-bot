package com.avishai.bot.core;

import com.avishai.bot.handlers.commands.CommandHandler;
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
        for (String signature : handler.getCommandSignature()) {
            commandRegistry.put(signature, handler);
            log.info("Registered command: {}", signature);
        }
    }

    public void route(Update update, MessageSender messageSender) {
        long incomingChatId;
        String messageText;

        if (update.hasMessage() && update.getMessage().hasText()) {
            incomingChatId = update.getMessage().getChatId();
            messageText = update.getMessage().getText();
        } else if (update.hasCallbackQuery()) {
            incomingChatId = update.getCallbackQuery().getMessage().getChatId();
            messageText = update.getCallbackQuery().getData();
        } else return;

        String chatIdStr = String.valueOf(incomingChatId);

        if (incomingChatId != Config.AUTHORIZED_CHAT_ID) {
            log.warn("Unauthorized access attempt from Chat ID: {}", incomingChatId);
            return;
        }

        if (messageText != null && messageText.startsWith("/")) {
            String commandSignature = messageText.split(" ")[0];
            CommandHandler handler = commandRegistry.get(commandSignature);

            if (handler != null) {
                log.info("Executing command: {}", commandSignature);
                handler.handle(new CommandContext(
                        commandSignature,
                        update,
                        chatIdStr,
                        messageSender
                ));
            } else handleUnknownCommand(chatIdStr, messageSender);
        }
    }

    public void handleUnknownCommand(String chatId, MessageSender messageSender) {
        messageSender.sendMessage(chatId,
                "⚠️ <b>Unknown Command</b>\n" +
                        "I don't recognize that instruction. Type /help to see the available modules.");
    }
}
