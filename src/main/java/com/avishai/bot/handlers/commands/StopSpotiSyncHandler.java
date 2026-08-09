package com.avishai.bot.handlers.commands;

import com.avishai.bot.core.MessageSender;
import com.avishai.bot.utils.BotCommands;
import org.telegram.telegrambots.meta.api.objects.Update;

public class StopSpotiSyncHandler implements CommandHandler {
    private final SpotiSyncHandler syncHandler;

    public StopSpotiSyncHandler(SpotiSyncHandler syncHandler) {
        this.syncHandler = syncHandler;
    }

    @Override
    public String getCommandSignature() {
        return BotCommands.STOP_SPOTIFY_BACKUP;
    }

    @Override
    public void handle(Update update, MessageSender messageSender) {
        String chatId = update.getMessage().getChatId().toString();
        syncHandler.abortSync(chatId, messageSender);
    }
}
