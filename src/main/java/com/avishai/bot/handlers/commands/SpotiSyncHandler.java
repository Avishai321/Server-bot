package com.avishai.bot.handlers.commands;

import com.avishai.bot.core.CommandContext;
import com.avishai.bot.core.Config;
import com.avishai.bot.models.SpotiSyncState;
import com.avishai.bot.utils.BotCommands;
import lombok.extern.slf4j.Slf4j;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.InlineKeyboardMarkup;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.buttons.InlineKeyboardButton;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Slf4j
public class SpotiSyncHandler implements CommandHandler {
    private final ExecutorService executorService;
    private final AtomicBoolean isSyncing = new AtomicBoolean(false);

    private final Pattern playlistMarkerPattern = Pattern.compile(
            "PLAYLIST_MARKER:\\s*(\\d+)/(\\d+)\\s*-\\s*(.*)");
    private final Pattern foundSongsPattern = Pattern.compile("(?i)Found (\\d+) songs");
    private final Pattern downloadedPattern = Pattern.compile("(?i)Downloaded \"(.+)\"");
    private final Pattern errorPattern = Pattern.compile("(?i)(failed to download|skipped|error:)");
    private final Pattern searchingPattern = Pattern.compile("(?i)Searching for (.+)");
    private final Pattern skippingPattern = Pattern.compile("(?i)Skipping (.+)");
    private volatile Process currentProcess = null;

    public SpotiSyncHandler(ExecutorService executorService) {
        this.executorService = executorService;

        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            if (this.currentProcess != null) {
                log.warn("JVM Shutting down: Force-killing SpotiSync child processes...");
                this.currentProcess.descendants().forEach(ProcessHandle::destroyForcibly);
                this.currentProcess.destroyForcibly();
            }
        }));
    }

    @Override
    public List<String> getCommandSignature() {
        return Arrays.asList(BotCommands.SPOTIFY_BACKUP, BotCommands.STOP_SPOTIFY_BACKUP);
    }

    @Override
    public void handle(CommandContext ctx) {
        switch (ctx.command()) {
            case BotCommands.SPOTIFY_BACKUP -> triggerSync(ctx);
            case BotCommands.STOP_SPOTIFY_BACKUP -> abortSync(ctx);
        }
    }

    private InlineKeyboardMarkup getActiveTaskKeyboard() {
        InlineKeyboardMarkup markupInline = new InlineKeyboardMarkup();
        List<List<InlineKeyboardButton>> rowsInline = new ArrayList<>();
        List<InlineKeyboardButton> rowInline = new ArrayList<>();

        InlineKeyboardButton abortBtn = new InlineKeyboardButton();
        abortBtn.setText("🛑 Abort");
        abortBtn.setCallbackData(BotCommands.STOP_SPOTIFY_BACKUP);

        rowInline.add(abortBtn);
        rowsInline.add(rowInline);
        markupInline.setKeyboard(rowsInline);
        return markupInline;
    }

    public void triggerSync(CommandContext ctx) {
        if (!isSyncing.compareAndSet(false, true)) {
            ctx.reply("⚠️ A sync is already in progress!\n🔴 Type " +
                    BotCommands.STOP_SPOTIFY_BACKUP + " to terminate it.");
            return;
        }

        Integer messageId = ctx.reply("""
                        📋 <b>TASK:</b> Spotify Music Sync
                        💡 <b>STATUS:</b> Initializing ⏳
                        🎵 <b>Track:</b> <i>Connecting...</i>""",
                getActiveTaskKeyboard()
        );

        if (messageId == null) {
            isSyncing.set(false);
            return;
        }
        executorService.submit(() -> executeSyncProcess(ctx, messageId));
    }

    public void abortSync(CommandContext ctx) {
        if (isSyncing.get() && currentProcess != null) {
            log.warn("Abort signal received! Terminating process tree...");
            currentProcess.descendants().forEach(ProcessHandle::destroyForcibly);
            currentProcess.destroyForcibly();
            ctx.reply("🛑 <b>Abort Signal Sent!</b>\nThe sync process is being forcibly terminated.");
        } else {
            ctx.reply("⚠️ No sync process is currently running.");
        }
    }

    private void executeSyncProcess(CommandContext ctx, Integer messageId) {
        Process process = null;
        try {
            ProcessBuilder pb = new ProcessBuilder("bash", Config.SPOTIFY_BACKUP_SCRIPT_PATH);
            pb.redirectErrorStream(true);
            process = pb.start();
            this.currentProcess = process;

            SpotiSyncState state = new SpotiSyncState();

            try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
                String line;
                long lastUpdateTime = 0;
                while ((line = reader.readLine()) != null) {
                    if (parseLineAndUpdateState(line, state)) {
                        long currentTime = System.currentTimeMillis();
                        if (currentTime - lastUpdateTime > Config.TELEGRAM_UPDATE_INTERVAL_MS) {
                            ctx.edit(messageId, state.renderCard(), getActiveTaskKeyboard());
                            lastUpdateTime = currentTime;
                        }
                    }
                }
            }

            int exitCode = process.waitFor();
            if (exitCode == 137 || exitCode == 143) {
                state.globalStatus = "Aborted 🛑";
                state.currentTrackName = "Process killed by user.";
            } else if (exitCode == 0) {
                state.globalStatus = "Completed ✅";
                state.currentTrackName = "All tracks finished smoothly.";
                if (state.tracksInCurrentPlaylist > 0) {
                    state.tracksProcessedInCurrent = state.tracksInCurrentPlaylist;
                }
            } else {
                state.globalStatus = "Failed ❌ (Code: " + exitCode + ")";
            }
            ctx.edit(messageId, state.renderCard());

        } catch (Exception e) {
            log.error("Execution error", e);
            ctx.edit(messageId, "⚠️ Critical Error:\n" + e.getMessage());
        } finally {
            this.currentProcess = null;
            isSyncing.set(false);
            if (process != null) {
                process.descendants().forEach(ProcessHandle::destroyForcibly);
                process.destroyForcibly();
            }
        }
    }

    private boolean parseLineAndUpdateState(String line, SpotiSyncState state) {
        Matcher playlistMatcher = playlistMarkerPattern.matcher(line);
        if (playlistMatcher.find()) {
            state.currentPlaylistNum = Integer.parseInt(playlistMatcher.group(1));
            state.totalPlaylists = Integer.parseInt(playlistMatcher.group(2));
            state.currentPlaylistName = playlistMatcher.group(3);
            state.tracksProcessedInCurrent = 0;
            state.tracksInCurrentPlaylist = 0;
            state.currentTrackName = "Scanning Spotify...";
            return true;
        }

        Matcher foundMatcher = foundSongsPattern.matcher(line);
        if (foundMatcher.find()) {
            state.tracksInCurrentPlaylist = Integer.parseInt(foundMatcher.group(1));
            return true;
        }

        Matcher downMatcher = downloadedPattern.matcher(line);
        if (downMatcher.find()) {
            state.tracksProcessedInCurrent++;
            state.globalDownloaded++;
            state.currentTrackName = "⬇️ Downloaded: " + downMatcher.group(1);
            return true;
        }

        Matcher skipMatcher = skippingPattern.matcher(line);
        if (skipMatcher.find()) {
            state.tracksProcessedInCurrent++;
            state.currentTrackName = "⏩ Skipped: " + skipMatcher.group(1);
            return true;
        }

        Matcher searchMatcher = searchingPattern.matcher(line);
        if (searchMatcher.find()) {
            state.currentTrackName = "🔍 Matching: " + searchMatcher.group(1);
            return true;
        }

        Matcher errMatcher = errorPattern.matcher(line);
        if (errMatcher.find()) {
            state.globalFailed++;
            return true;
        }

        if (line.toLowerCase().contains("scanning files into nextcloud database")) {
            state.globalStatus = "Updating Nextcloud 🔄";
            state.currentTrackName = "Database Sync...";
            return true;
        }

        return false;
    }
}
