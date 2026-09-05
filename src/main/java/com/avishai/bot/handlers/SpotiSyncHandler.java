package com.avishai.bot.handlers;

import com.avishai.bot.config.BotCommands;
import com.avishai.bot.core.CommandContext;
import com.avishai.bot.core.TelegramUi;
import com.avishai.bot.services.SpotifyService;
import lombok.RequiredArgsConstructor;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.InlineKeyboardMarkup;

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
    public HandlerCategory getCategory() {
        return HandlerCategory.STORAGE_AND_MEDIA;
    }

    @Override
    public String getDescription() {
        return "Sync Spotify to Nextcloud";
    }

    @Override
    public String getDetailedHelp() {
        return """
                🎵 <b>Spotify Sync - Manual</b>
                
                Executes SpotDL via system shell and updates Nextcloud database.
                
                <b>Commands:</b>
                <code>/spotisync</code> - Start the sync process
                <code>/stop_spotisync</code> - Forcibly terminate the process tree""";
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
                    BotCommands.STOP_SPOTIFY_BACKUP));
            return;
        }

        Integer messageId = ctx.reply("""
                        🎵 <b>TASK:</b> Spotify Music Sync
                        
                        🚀 <b>STATUS:</b> Initializing...
                        🎧 <b>Track:</b> <i>Connecting...</i>""",
                TelegramUi.singleButtonKeyboard("🛑 Abort", BotCommands.STOP_SPOTIFY_BACKUP));

        if (messageId != null) {
            executorService.submit(() -> spotifyService.runSync(state -> {
                InlineKeyboardMarkup keyboard = state.isActive()
                        ? TelegramUi.singleButtonKeyboard("🛑 Abort", BotCommands.STOP_SPOTIFY_BACKUP)
                        : null;

                ctx.edit(messageId, state.renderCard(), keyboard);
            }));
        }
    }

    private void abortSync(CommandContext ctx) {
        if (spotifyService.isBusy()) {
            spotifyService.abortSync();
            ctx.reply("🛑 <b>Abort Signal Sent!</b>\nThe sync process is being forcibly terminated.");
        } else ctx.reply("ℹ️ No sync process is currently running.");
    }
}
