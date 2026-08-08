package com.avishai.bot.handlers;

import com.avishai.bot.core.Config;
import org.slf4j.Logger;
import com.avishai.bot.core.MessageSender;
import com.avishai.bot.utils.BotCommands;
import org.slf4j.LoggerFactory;
import org.telegram.telegrambots.meta.api.objects.Update;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class SpotifyBackupCommandHandler implements CommandHandler {
    private static final Logger log = LoggerFactory.getLogger(SpotifyBackupCommandHandler.class);
    private final ExecutorService executorService = Executors.newCachedThreadPool();
    private static final long TELEGRAM_UPDATE_INTERVAL_MS = 3000;

    @Override
    public String getCommandSignature() {
        return BotCommands.SPOTIFY_BACKUP;
    }

    @Override
    public void handle(Update update, MessageSender messageSender) {
        String chatId = update.getMessage().getChatId().toString();
        Integer messageId = messageSender.sendMessage(
                chatId,
                "Spotify Sync Initializing... \n[Processing...]"
        );

        if (messageId == null) {
            log.error("Failed to send initial message. Cannot track progress.");
            return;
        }

        executorService.submit(() -> executorSyncProcess(chatId, messageId, messageSender));
    }

    private void executorSyncProcess(String chatId, Integer messageId, MessageSender messageSender) {
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
                    if (currentTime - lastUpdateTime > TELEGRAM_UPDATE_INTERVAL_MS) {
                        messageSender.editMessage(chatId,
                                messageId,
                                "\uD83C\uDFA7 Spotify Sync in Progress:\n" + latestStatus
                        );
                        lastUpdateTime = currentTime;
                    }
                }
            }

            int exitCode = process.waitFor();
            if (exitCode == 0) {messageSender.editMessage(
                    chatId,
                    messageId,
                    "✅ Spotify Sync Completed Successfully!");
            } else messageSender.editMessage(
                    chatId,
                    messageId,
                    "❌ Spotify Sync Failed\nExit code: " + exitCode);
        } catch (Exception e) {
            log.error("Execution error", e);
            messageSender.editMessage(chatId,
                    messageId,
                    "⚠️ Critical Error during execution:\n" + e.getMessage());
        } finally {
            if (process != null) process.destroy();
        }
    }

    private String extractRelevantStatus(String line, String previousStatus) {
        if (line.toLowerCase().contains("processing playlist")) {
            return "⏳" + line.trim();
        } else if (line.toLowerCase().contains("downloaded")) {
            return "⬇️" + line.trim();
        } else if (line.toLowerCase().contains("scanning files")) {
            return "\uD83D\uDD04 Updating Nextcloud Database";
        }
        return previousStatus;
    }
}
