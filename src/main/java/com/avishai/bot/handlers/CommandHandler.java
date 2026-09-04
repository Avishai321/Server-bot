package com.avishai.bot.handlers;

import com.avishai.bot.core.CommandContext;

import java.util.List;

public interface CommandHandler {
    List<String> getCommandSignature();

    default String getCategory() {
        return "Other";
    }

    default String getDescription() {
        return "";
    }

    default String getDetailedHelp() {
        return "No detailed documentation available";
    }

    void handle(CommandContext ctx);
}
