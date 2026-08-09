package com.avishai.bot.handlers;

import com.avishai.bot.core.Config;
import com.avishai.bot.core.MessageSender;
import com.avishai.bot.utils.BotCommands;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.telegram.telegrambots.meta.api.objects.Update;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

public class SpotiSyncCommandHandler implements CommandHandler {
    private static final Logger log = LoggerFactory.getLogger(SpotiSyncCommandHandler.class);
    private final ExecutorService executorService = Executors.newCachedThreadPool();

    private final AtomicBoolean isSyncing = new AtomicBoolean(false);

    @Override
    public String getCommandSignature() {
        return BotCommands.SPOTIFY_BACKUP;
    }

    @Override
    public void handle(Update update, MessageSender messageSender) {
        String chatId = update.getMessage().getChatId().toString();
        triggerSync(chatId, messageSender);
    }

    public void triggerSync(String chatId, MessageSender messageSender) {
        if (!isSyncing.compareAndSet(false, true)) {
            messageSender.sendMessage(chatId,
                    "⚠️ A sync is already in progress. Please wait until it finishes!");
            return;
        }

        Integer messageId = messageSender.sendMessage(
                chatId,
                "\uD83C\uDFA7 Spotify Sync Initializing...\n[Connecting to server...]");
        if (messageId == null) {
            log.error("Failed to send initial message. Cannot track progress.");
            isSyncing.set(false);
            return;
        }

        executorService.submit(() -> executeSyncProcess(chatId, messageId, messageSender));
    }

    private void executeSyncProcess(String chatId, Integer messageId, MessageSender messageSender) {
        Process process = null;
        try {
            ProcessBuilder processBuilder = new ProcessBuilder(Config.SPOTIFY_BACKUP_SCRIPT_PATH);
            processBuilder.redirectErrorStream(true);
            process = processBuilder.start();

            try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
                String line;
                long lastUpdateTime = 0;
                String latestStatus = "Starting...";

                while ((line = reader.readLine()) != null) {
                    log.info("[SpotDL] {}", line);
                    latestStatus = extractRelevantStatus(line, latestStatus);

                    long currentTime = System.currentTimeMillis();
                    if (currentTime - lastUpdateTime > Config.TELEGRAM_UPDATE_INTERVAL_MS) {
                        messageSender.editMessage(chatId, messageId,
                                "\uD83C\uDFA7 Spotify Sync in Progress:\n\n" + latestStatus);
                        lastUpdateTime = currentTime;
                    }
                }
            }

            int exitCode = process.waitFor();
            if (exitCode == 0) {
                messageSender.editMessage(chatId, messageId,
                        "✅ Spotify Sync Completed Successfully!\nNextcloud client index updated.");
            } else messageSender.editMessage(chatId, messageId,
                    "❌ Spotify Sync Failed\nExit code: " + exitCode);
        } catch (Exception e) {
            log.error("Execution error", e);
            messageSender.editMessage(chatId, messageId,
                    "⚠️ Critical Error during execution:\n" + e.getMessage());
        } finally {
            isSyncing.set(false);
            if (process != null) process.destroy();
        }
    }

    private String extractRelevantStatus(String line, String previousStatus) {
        String lowerLine = line.toLowerCase();

        if (line.startsWith("PLAYLIST_MARKER:")) {
            return "\uD83D\uDCC2 " + line.replace("PLAYLIST_MARKER:", "").trim()
                    + "\n⏳ Scanning Spotify...";
        } else if (lowerLine.contains("downloaded \"") || lowerLine.contains("downloaded: ")) {
            return previousStatus + "\n⬇️ Downloaded a new track!";
        } else if (lowerLine.contains("scanning files")) {
            return "\uD83D\uDD04 Updating Nextcloud Database...\n(This might take a moment)";
        }
        return previousStatus;
    }
}
