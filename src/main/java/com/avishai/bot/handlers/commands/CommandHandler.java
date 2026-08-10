package com.avishai.bot.handlers.commands;

import com.avishai.bot.core.CommandContext;

import java.util.List;

public interface CommandHandler {
    List<String> getCommandSignature();
    void handle(CommandContext ctx);
}
