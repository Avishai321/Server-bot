package com.avishai.bot.handlers.commands;

import com.avishai.bot.core.Config;
import com.avishai.bot.core.MessageSender;
import com.avishai.bot.utils.BotCommands;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.telegram.telegrambots.meta.api.objects.Update;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.InlineKeyboardMarkup;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.buttons.InlineKeyboardButton;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class SpotiSyncHandler implements CommandHandler {
    private static final Logger log = LoggerFactory.getLogger(SpotiSyncHandler.class);
    private final ExecutorService executorService = Executors.newCachedThreadPool();
    private final AtomicBoolean isSyncing = new AtomicBoolean(false);

    private volatile Process currentProcess = null;

    private final Pattern playlistMarkerPattern = Pattern.compile(
            "PLAYLIST_MARKER:\\s*(\\d+)/(\\d+)\\s*-\\s*(.*)");
    private final Pattern foundSongsPattern = Pattern.compile("(?i)Found (\\d+) songs");
    private final Pattern downloadedPattern = Pattern.compile("(?i)Downloaded \"(.+)\"");
    private final Pattern errorPattern = Pattern.compile("(?i)(failed to download|skipped|error:)");

    @Override
    public String getCommandSignature() {
        return BotCommands.SPOTIFY_BACKUP;
    }

    @Override
    public void handle(Update update, String chatId, MessageSender messageSender) {
        triggerSync(chatId, messageSender);
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

    public void triggerSync(String chatId, MessageSender messageSender) {
        if (!isSyncing.compareAndSet(false, true)) {
            messageSender.sendMessage(chatId,
                    "⚠️ A sync is already in progress. Please wait until it finishes!\n" +
                            "\uD83D\uDD34 Type " + BotCommands.STOP_SPOTIFY_BACKUP + " to terminate it");
            return;
        }

        Integer messageId = messageSender.sendMessage(chatId,
                """
                        📋 <b>TASK:</b> Spotify Music Sync
                        ━━━━━━━━━━━━━━━━━━
                        💡 <b>STATUS:</b> Initializing ⏳
                        🎵 <b>Track:</b> <i>Connecting to server...</i>""",
                getActiveTaskKeyboard());

        if (messageId == null) {
            log.error("Failed to send initial message. Cannot track progress.");
            isSyncing.set(false);
            return;
        }

        executorService.submit(() -> executeSyncProcess(chatId, messageId, messageSender));
    }

    public void abortSync(String chatId, MessageSender messageSender) {
        if (isSyncing.get() && currentProcess != null) {
            log.warn("Abort signal received! Terminating process tree...");

            currentProcess.descendants().forEach(ProcessHandle::destroyForcibly);
            currentProcess.destroyForcibly();

            messageSender.sendMessage(chatId,
                    "🛑 <b>Abort Signal Sent!</b>\n" +
                            "The sync process is being forcibly terminated.");
        }
        else messageSender.sendMessage(chatId, "⚠️ No sync process is currently running.");
    }

    private void executeSyncProcess(String chatId, Integer messageId, MessageSender messageSender) {
        Process process = null;
        try {
            ProcessBuilder processBuilder = new ProcessBuilder(
                    "bash",
                    Config.SPOTIFY_BACKUP_SCRIPT_PATH
            );
            processBuilder.redirectErrorStream(true);
            process = processBuilder.start();

            this.currentProcess = process;
            SyncState state = new SyncState();

            try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
                String line;
                long lastUpdateTime = 0;

                while ((line = reader.readLine()) != null) {
                    log.info("[SpotDL] {}", line);
                    boolean stateChanged = parseLineAndUpdateState(line, state);

                    long currentTime = System.currentTimeMillis();

                    if (stateChanged) {
                        if (currentTime - lastUpdateTime > Config.TELEGRAM_UPDATE_INTERVAL_MS) {
                            messageSender.editMessage(
                                    chatId,
                                    messageId,
                                    state.renderCard(),
                                    getActiveTaskKeyboard());
                            lastUpdateTime = currentTime;
                        }
                    }
                }
            }

            int exitCode = process.waitFor();
            log.info("Process finished with exit code: {}", exitCode);

            if (exitCode == 137 || exitCode == 143) {
                state.globalStatus = "Aborted 🛑";
                state.currentTrackName = "Process killed by user.";
                messageSender.editMessage(chatId, messageId, state.renderCard());
            } else if (exitCode == 0) {
                state.globalStatus = "Completed ✅";
                messageSender.editMessage(chatId, messageId, state.renderCard());
            } else {
                state.globalStatus = "Failed ❌ (Code: " + exitCode + ")";
                messageSender.editMessage(chatId, messageId, state.renderCard());
            }
        } catch (Exception e) {
            log.error("Execution error", e);
            messageSender.editMessage(
                    chatId, messageId,
                    "⚠️ Critical Error during execution:\n" + e.getMessage());
        } finally {
            this.currentProcess = null;
            isSyncing.set(false);
            if (process != null) {
                process.descendants().forEach(ProcessHandle::destroyForcibly);
                process.destroyForcibly();
            }
        }
    }

    private boolean parseLineAndUpdateState(String line, SyncState state) {
        Matcher playlistMatcher = playlistMarkerPattern.matcher(line);
        if (playlistMatcher.find()) {
            state.currentPlaylistNum = Integer.parseInt(playlistMatcher.group(1));
            state.totalPlaylists = Integer.parseInt(playlistMatcher.group(2));
            state.currentPlaylistName = playlistMatcher.group(3);
            state.tracksDownloadedInCurrent = 0;
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
            state.tracksDownloadedInCurrent++;
            state.globalDownloaded++;
            state.currentTrackName = downMatcher.group(1);
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

    private static class SyncState {
        int currentPlaylistNum = 0;
        int totalPlaylists = 0;
        String currentPlaylistName = "Initializing...";
        int tracksInCurrentPlaylist = 0;
        int tracksDownloadedInCurrent = 0;
        String currentTrackName = "Waking up process...";
        int globalDownloaded = 0;
        int globalFailed = 0;
        String globalStatus = "Running 🟢";

        String renderCard() {
            StringBuilder sb = new StringBuilder();
            sb.append("📋 <b>TASK:</b> Spotify Music Sync\n");
            sb.append("━━━━━━━━━━━━━━━━━━\n");
            sb.append("💡 <b>STATUS:</b> ").append(globalStatus).append("\n");

            if (totalPlaylists > 0) {
                sb.append("📂 <b>Playlist:</b> <code>")
                        .append(escapeHtml(currentPlaylistName)).append("</code> (")
                        .append(currentPlaylistNum).append("/").append(totalPlaylists).append(")\n");

                int percentage = 0;
                if (tracksInCurrentPlaylist > 0) {
                    percentage = (int) (((double) tracksDownloadedInCurrent / tracksInCurrentPlaylist) * 100);
                }

                sb.append("📊 <b>Progress:</b> <code>")
                        .append(generateProgressBar(percentage)).append("</code> ")
                        .append(tracksDownloadedInCurrent).append(" / ")
                        .append(tracksInCurrentPlaylist).append(" tracks\n");
            }

            sb.append("🎵 <b>Track:</b> <i>").append(escapeHtml(currentTrackName)).append("</i>\n");
            sb.append("━━━━━━━━━━━━━━━━━━\n");
            sb.append("📥 Total saved this run: ").append(globalDownloaded).append("\n");
            sb.append("⚠️ Skipped/Failed: ").append(globalFailed);

            return sb.toString();
        }

        private String generateProgressBar(int percentage) {
            int totalBars = 10;
            int filledBars = (percentage * totalBars) / 100;
            filledBars = Math.max(0, Math.min(filledBars, totalBars));

            StringBuilder bar = new StringBuilder("[");
            for (int i = 0; i < totalBars; i++) {
                if (i < filledBars) bar.append("█");
                else bar.append("░");
            }
            bar.append("]");

            return bar.toString();
        }

        // Prevents Telegram from crashing if a song has "<" or "&" in its name
        private String escapeHtml(String text) {
            if (text == null) return "";
            return text.replace("&", "&amp;")
                    .replace("<", "&lt;")
                    .replace(">", "&gt;");
        }
    }
}
