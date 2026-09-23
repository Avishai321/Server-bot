package com.avishai.bot.routing;

import com.avishai.bot.config.Config;
import com.avishai.bot.handlers.CommandHandler;
import com.avishai.bot.util.TelegramUi;
import lombok.extern.slf4j.Slf4j;
import org.telegram.telegrambots.meta.api.objects.Update;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Slf4j
public class UpdateRouter {
    private final Map<String, CommandHandler> commandRegistry;
    private final List<String> allSignatures;

    public UpdateRouter() {
        this.commandRegistry = new HashMap<>();
        this.allSignatures = new ArrayList<>();
    }

    public void registerCommand(CommandHandler handler) {
        if (!handler.getClass().isAnnotationPresent(BotCommand.class)) {
            log.warn("Handler {} is missing @BotCommand annotation!",
                    handler.getClass().getSimpleName());
            return;
        }

        BotCommand annotation = handler.getClass().getAnnotation(BotCommand.class);
        for (String signature : annotation.command()) {
            commandRegistry.put(signature, handler);
            allSignatures.add(signature);
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

        if (!chatIdStr.equals(Config.AUTHORIZED_CHAT_ID)) {
            log.warn("Unauthorized access attempt from Chat ID: {}", incomingChatId);
            return;
        }

        if (messageText != null && messageText.startsWith("/")) {
            String commandSignature = messageText.split(" ")[0];
            CommandHandler handler = commandRegistry.get(commandSignature);

            if (handler != null) {
                log.info("Executing command: {}", commandSignature);
                try {
                    handler.handle(new CommandContext(
                            commandSignature,
                            update,
                            chatIdStr,
                            messageSender
                    ));
                } catch (Exception e) {
                    log.error("Unhandled JVM exception in {}",
                            handler.getClass().getSimpleName(), e
                    );

                    String errorUi = String.format("""
                            <b>SYSTEM FAULT</b>
                            An unexpected internal error occurred.
                            
                            <b>Trace:</b>
                            <pre>%s</pre>""", TelegramUi.escapeHtml(e.getMessage()));
                    messageSender.sendMessage(chatIdStr, errorUi);
                }
            } else handleUnknownCommand(commandSignature, chatIdStr, messageSender);
        }
    }

    private void handleUnknownCommand(String unknownCommand,
                                      String chatId,
                                      MessageSender messageSender) {
        String suggestion = findClosestCommand(unknownCommand);

        String response = "<b>Unknown Command</b>" +
                "\nI don't recognize that instruction.";

        if (suggestion != null) {
            response += String.format("\n\nDid you mean %s?", suggestion);
        }
        response += "\n\nType /help to see the available modules.";

        messageSender.sendMessage(chatId, response);
    }

    private String findClosestCommand(String input) {
        String closest = null;
        int minDistance = Integer.MAX_VALUE;

        for (String cmd : allSignatures) {
            int dist = levenshteinDistance(input.toLowerCase(), cmd.toLowerCase());
            if (dist < minDistance && dist <= 3) { // Threshold for similarity
                minDistance = dist;
                closest = cmd;
            }
        }
        return closest;
    }

    private int levenshteinDistance(String a, String b) {
        int[] costs = new int[b.length() + 1];
        for (int j = 0; j < costs.length; j++) costs[j] = j;
        for (int i = 1; i <= a.length(); i++) {
            costs[0] = i;
            int nw = i - 1;
            for (int j = 1; j <= b.length(); j++) {
                int cj = Math.min(1 + Math.min(costs[j], costs[j - 1]),
                        a.charAt(i - 1) == b.charAt(j - 1) ? nw : nw + 1);
                nw = costs[j];
                costs[j] = cj;
            }
        }
        return costs[b.length()];
    }
}
