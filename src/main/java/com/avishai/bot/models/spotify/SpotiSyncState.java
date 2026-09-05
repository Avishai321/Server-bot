package com.avishai.bot.models.spotify;

import com.avishai.bot.core.TelegramUi;
import lombok.Data;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

@Data
public class SpotiSyncState {
    private final AtomicBoolean active = new AtomicBoolean(true);
    // High-concurrency counters (Modified concurrently by the thread pool)
    private final AtomicInteger tracksProcessedInCurrent = new AtomicInteger(0);
    private final AtomicInteger globalDownloaded = new AtomicInteger(0);
    private final AtomicInteger globalFailed = new AtomicInteger(0);
    private final AtomicInteger globalSkipped = new AtomicInteger(0);
    private final AtomicReference<String> currentTrackName = new AtomicReference<>("Waking up process...");
    private final AtomicReference<String> globalStatus = new AtomicReference<>("Running...");
    // Standard properties (Only modified by the main orchestrator thread)
    private int currentPlaylistNum = 0;
    private int totalPlaylists = 0;
    private String currentPlaylistName = "Initializing...";
    private int tracksInCurrentPlaylist = 0;

    public void addSkipped(int count) {
        globalSkipped.addAndGet(count);
    }

    public void markTrackSuccess(String trackName) {
        tracksProcessedInCurrent.incrementAndGet();
        globalDownloaded.incrementAndGet();
        currentTrackName.set(trackName);
    }

    public void markTrackFailed(String trackName) {
        tracksProcessedInCurrent.incrementAndGet();
        globalFailed.incrementAndGet();
        currentTrackName.set("Error: " + trackName);
    }

    public boolean isActive() {
        return active.get();
    }

    public String renderCard() {
        int processed = tracksProcessedInCurrent.get();
        int percentage = (tracksInCurrentPlaylist > 0)
                ? (int) (((double) processed / tracksInCurrentPlaylist) * 100)
                : 0;

        String playlistInfo = (totalPlaylists > 0) ? String.format("""
                        <b>Playlist:</b> <code>%s</code> (%d/%d)
                        <b>Progress:</b> <code>%s</code> %d / %d
                        """,
                TelegramUi.escapeHtml(currentPlaylistName),
                currentPlaylistNum,
                totalPlaylists,
                TelegramUi.progressBar(percentage, 10),
                processed,
                tracksInCurrentPlaylist) : "";

        return String.format("""
                        <b>TASK:</b> Spotify Music Sync
                         \s
                        <b>STATUS:</b> %s
                        %s<b>Track:</b> <i>%s</i>
                         \s
                         Downloaded: %d  | Skipped: %d  | Failed: %d""",
                globalStatus.get(),
                playlistInfo,
                TelegramUi.escapeHtml(currentTrackName.get()),
                globalDownloaded.get(),
                globalSkipped.get(),
                globalFailed.get());
    }
}
