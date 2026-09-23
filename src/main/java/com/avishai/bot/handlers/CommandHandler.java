package com.avishai.bot.handlers;

import com.avishai.bot.routing.CommandContext;

public interface CommandHandler {
    void handle(CommandContext ctx);
}
