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
    private final Pattern playlistMarkerPattern = Pattern.compile(
            "PLAYLIST_MARKER:\\s*(\\d+)/(\\d+)\\s*-\\s*(.*)");
    private final Pattern foundSongsPattern = Pattern.compile("(?i)Found (\\d+) songs");
    private final Pattern downloadedPattern = Pattern.compile("(?i)Downloaded \"(.+)\"");
    private final Pattern errorPattern = Pattern.compile("(?i)(failed to download|skipped|error:)");
    private final Pattern searchingPattern = Pattern.compile("(?i)Searching for (.+)");
    private final Pattern skippingPattern = Pattern.compile("(?i)Skipping (.+)");
    private volatile Process currentProcess = null;

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
                state.currentTrackName = "Process killed by user.";
            } else if (exitCode == 0) {
                state.globalStatus = "Completed ✅";
                state.currentTrackName = "All tracks finished smoothly.";
                if (state.tracksInCurrentPlaylist > 0) state.tracksProcessedInCurrent = state.tracksInCurrentPlaylist;
            } else state.globalStatus = "Failed ❌ (Code: " + exitCode + ")";

            onStateUpdate.accept(state);

        } catch (Exception e) {
            log.error("Execution error", e);
            state.globalStatus = "Critical Error";
            state.currentTrackName = e.getMessage();
            onStateUpdate.accept(state);
        } finally {
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
            state.currentTrackName = "✅ Downloaded: " + downMatcher.group(1);
            return true;
        }
        Matcher skipMatcher = skippingPattern.matcher(line);
        if (skipMatcher.find()) {
            state.tracksProcessedInCurrent++;
            state.currentTrackName = "⏭️ Skipped: " + skipMatcher.group(1);
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
            state.globalStatus = "Updating Nextcloud ⚙️";
            state.currentTrackName = "Database Sync...";
            return true;
        }
        return false;
    }
}
