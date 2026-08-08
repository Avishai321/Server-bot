package com.avishai.bot.handlers;

import com.avishai.bot.core.MessageSender;
import com.avishai.bot.utils.BotCommands;
import org.telegram.telegrambots.meta.api.objects.Update;

public class SpotifyBackupCommandHandler implements CommandHandler {
    @Override
    public String getCommandSignature() {
        return BotCommands.SPOTIFY_BACKUP;
    }

    @Override
    public void handle(Update update, MessageSender messageSender) {
        messageSender.sendMessage(update.getMessage().getChatId().toString(), "Spotifuckkkkk");
    }
}
