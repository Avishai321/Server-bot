package com.avishai.bot.models;

import com.avishai.bot.core.TelegramUi;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class SpotiSyncState {
    public int currentPlaylistNum = 0;
    public int totalPlaylists = 0;
    public String currentPlaylistName = "Initializing...";
    public int tracksInCurrentPlaylist = 0;
    public int tracksProcessedInCurrent = 0;
    public String currentTrackName = "Waking up process...";
    public int globalDownloaded = 0;
    public int globalFailed = 0;
    public String globalStatus = "Running ⚙️";

    public String renderCard() {
        int percentage = (tracksInCurrentPlaylist > 0)
                ? (int) (((double) tracksProcessedInCurrent / tracksInCurrentPlaylist) * 100)
                : 0;

        String playlistInfo = (totalPlaylists > 0) ? String.format("""
                📂 <b>Playlist:</b> <code>%s</code> (%d/%d)
                📊 <b>Progress:</b> <code>%s</code> %d / %d tracks
                """,
                TelegramUi.escapeHtml(currentPlaylistName),
                currentPlaylistNum,
                totalPlaylists,
                TelegramUi.progressBar(percentage, 10),
                tracksProcessedInCurrent,
                tracksInCurrentPlaylist) : "";

        return String.format("""
                🎵 <b>TASK:</b> Spotify Music Sync
                
                🚀 <b>STATUS:</b> %s
                %s🎧 <b>Track:</b> <i>%s</i>
                
                💾 Total saved: %d
                ⏭️ Skipped/Failed: %d""",
                globalStatus,
                playlistInfo,
                TelegramUi.escapeHtml(currentTrackName),
                globalDownloaded,
                globalFailed);
    }
}
