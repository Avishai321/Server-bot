package com.avishai.bot.services;

import com.avishai.bot.config.Config;
import com.avishai.bot.models.SpotiSyncState;
import lombok.extern.slf4j.Slf4j;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Slf4j
public class SpotifyService {
    private final AtomicBoolean isSyncing = new AtomicBoolean(false);
    private final Pattern playlistPattern = Pattern.compile(
            "^\\[BOT:PLAYLIST] (\\d+) (\\d+) (.*)");
    private final Pattern phasePattern = Pattern.compile(
            "^\\[BOT:PHASE] (.*)");
    private final Pattern foundPattern = Pattern.compile(
            "(?i)Found (\\d+) songs");
    private final Pattern downloadedPattern = Pattern.compile(
            "(?i)Downloaded \"([^\"]+)\"");
    private final Pattern skippedPattern = Pattern.compile(
            "(?i)Skipping (.*?)(?: \\(file already exists\\))?(?: \\(duplicate\\))?$");
    private final Pattern lookupErrorPattern = Pattern.compile(
            "(?i)LookupError: (.*)");
    private final Pattern errorPattern = Pattern.compile(
            "(?i)(AudioProviderError|FFmpegError|MetadataError|YT-DLP download error)");
    private volatile Process currentProcess = null;

    public SpotifyService() {
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            if (this.currentProcess != null) {
                log.warn("JVM Shutting down: Force-killing Spotify Sync child processes...");
                this.currentProcess.descendants().forEach(ProcessHandle::destroyForcibly);
                this.currentProcess.destroyForcibly();
            }
        }));
    }

    public boolean isBusy() {
        return isSyncing.get();
    }

    public void runSync(Consumer<SpotiSyncState> onStateUpdate) {
        if (!isSyncing.compareAndSet(false, true)) return;

        SpotiSyncState state = new SpotiSyncState();

        try {
            ProcessBuilder pb = new ProcessBuilder("bash", Config.SPOTIFY_BACKUP_SCRIPT_PATH);
            pb.redirectErrorStream(true);
            currentProcess = pb.start();

            try (BufferedReader reader = new BufferedReader(new InputStreamReader(currentProcess.getInputStream()))) {
                String line;
                long lastUpdateTime = 0;

                while ((line = reader.readLine()) != null) {
                    if (parseLineAndUpdateState(line, state)) {
                        long currentTime = System.currentTimeMillis();
                        if (currentTime - lastUpdateTime > Config.TELEGRAM_UPDATE_INTERVAL_MS) {
                            onStateUpdate.accept(state);
                            lastUpdateTime = currentTime;
                        }
                    }
                }
            }

            int exitCode = currentProcess.waitFor();

            if (exitCode == 137 || exitCode == 143) {
                state.globalStatus = "Aborted 🛑";
                state.currentTrackName = "Process forcibly terminated by user.";
            } else {
                state.globalStatus = "Completed ✅";
                state.currentTrackName = "All tasks finished.";
                if (state.tracksInCurrentPlaylist > 0) {
                    state.tracksProcessedInCurrent = state.tracksInCurrentPlaylist;
                }
            }

        } catch (Exception e) {
            log.error("Spotify execution error", e);
            state.globalStatus = "Critical Error ❌";
            state.currentTrackName = e.getMessage();
        } finally {
            state.setActive(false);
            onStateUpdate.accept(state);

            currentProcess = null;
            isSyncing.set(false);
        }
    }

    public void abortSync() {
        if (isSyncing.get() && currentProcess != null) {
            log.warn("Abort signal received! Terminating process tree...");
            currentProcess.descendants().forEach(ProcessHandle::destroyForcibly);
            currentProcess.destroyForcibly();
        }
    }

    private boolean parseLineAndUpdateState(String line, SpotiSyncState state) {
        Matcher playlistMatcher = playlistPattern.matcher(line);
        if (playlistMatcher.find()) {
            state.currentPlaylistNum = Integer.parseInt(playlistMatcher.group(1));
            state.totalPlaylists = Integer.parseInt(playlistMatcher.group(2));
            state.currentPlaylistName = playlistMatcher.group(3);
            state.tracksProcessedInCurrent = 0;
            state.tracksInCurrentPlaylist = 0;
            state.currentTrackName = "Scanning Spotify...";
            return true;
        }

        Matcher phaseMatcher = phasePattern.matcher(line);
        if (phaseMatcher.find()) {
            state.globalStatus = phaseMatcher.group(1) + " ⚙️";
            state.currentTrackName = "Processing...";
            return true;
        }

        Matcher foundMatcher = foundPattern.matcher(line);
        if (foundMatcher.find()) {
            state.tracksInCurrentPlaylist = Integer.parseInt(foundMatcher.group(1));
            return true;
        }

        Matcher downMatcher = downloadedPattern.matcher(line);
        if (downMatcher.find()) {
            state.tracksProcessedInCurrent++;
            state.globalDownloaded++;
            state.currentTrackName = downMatcher.group(1);
            return true;
        }

        Matcher skipMatcher = skippedPattern.matcher(line);
        if (skipMatcher.find()) {
            state.tracksProcessedInCurrent++;
            state.globalSkipped++;
            state.currentTrackName = skipMatcher.group(1).trim();
            return true;
        }

        Matcher lookupErrMatcher = lookupErrorPattern.matcher(line);
        if (lookupErrMatcher.find()) {
            state.tracksProcessedInCurrent++;
            state.globalFailed++;
            state.currentTrackName = "Not Found: " + lookupErrMatcher.group(1).trim();
            return true;
        }

        Matcher errMatcher = errorPattern.matcher(line);
        if (errMatcher.find()) {
            state.globalFailed++;
            state.currentTrackName = "Error: " + errMatcher.group(1);
            return true;
        }

        return false;
    }
}
