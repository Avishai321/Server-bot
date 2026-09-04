package com.avishai.bot.handlers;

import com.avishai.bot.config.BotCommands;
import com.avishai.bot.core.CommandContext;
import com.avishai.bot.services.SpotifyService;
import lombok.RequiredArgsConstructor;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.InlineKeyboardMarkup;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.buttons.InlineKeyboardButton;

import java.util.List;
import java.util.concurrent.ExecutorService;

@RequiredArgsConstructor
public class SpotiSyncHandler implements CommandHandler {
    private final ExecutorService executorService;
    private final SpotifyService spotifyService;

    @Override
    public List<String> getCommandSignature() {
        return List.of(BotCommands.SPOTIFY_BACKUP, BotCommands.STOP_SPOTIFY_BACKUP);
    }

    @Override
    public void handle(CommandContext ctx) {
        String action = ctx.getActionData();

        if (action.equals(BotCommands.SPOTIFY_BACKUP)) triggerSync(ctx);
        else if (action.equals(BotCommands.STOP_SPOTIFY_BACKUP)) abortSync(ctx);
    }

    private void triggerSync(CommandContext ctx) {
        if (spotifyService.isBusy()) {
            ctx.reply(String.format("⚠️ A sync is already in progress!\nType %s to terminate it.",
                    BotCommands.STOP_SPOTIFY_BACKUP)
            );
            return;
        }

        Integer messageId = ctx.reply("""
                🎵 <b>TASK:</b> Spotify Music Sync
                <b>STATUS:</b> Initializing...
                <b>Track:</b> <i>Connecting...</i>""", getActiveTaskKeyboard());

        if (messageId != null) {
            executorService.submit(() -> spotifyService.runSync(
                    state -> ctx.edit(messageId, state.renderCard(), getActiveTaskKeyboard())
            ));
        }
    }

    private void abortSync(CommandContext ctx) {
        if (spotifyService.isBusy()) {
            spotifyService.abortSync();
            ctx.reply("🛑 <b>Abort Signal Sent!</b>\nThe sync process is being forcibly terminated.");
        } else ctx.reply("ℹ️ No sync process is currently running.");
    }

    private InlineKeyboardMarkup getActiveTaskKeyboard() {
        InlineKeyboardMarkup markup = new InlineKeyboardMarkup();
        InlineKeyboardButton abortBtn = new InlineKeyboardButton();
        abortBtn.setText("🛑 Abort");
        abortBtn.setCallbackData(BotCommands.STOP_SPOTIFY_BACKUP);
        markup.setKeyboard(List.of(List.of(abortBtn)));
        return markup;
    }
}
