package com.avishai.bot.models;

public class SpotiSyncState {
    public int currentPlaylistNum = 0;
    public int totalPlaylists = 0;
    public String currentPlaylistName = "Initializing...";
    public int tracksInCurrentPlaylist = 0;
    public int tracksProcessedInCurrent = 0;
    public String currentTrackName = "Waking up process...";
    public int globalDownloaded = 0;
    public int globalFailed = 0;
    public String globalStatus = "Running 🟢";

    public String renderCard() {
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
                percentage = (int) (((double) tracksProcessedInCurrent / tracksInCurrentPlaylist) * 100);
            }

            sb.append("📊 <b>Progress:</b> <code>")
                    .append(generateProgressBar(percentage)).append("</code> ")
                    .append(tracksProcessedInCurrent).append(" / ")
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
        int filledBars = Math.max(0, Math.min((percentage * totalBars) / 100, totalBars));

        StringBuilder bar = new StringBuilder("[");
        for (int i = 0; i < totalBars; i++) {
            bar.append(i < filledBars ? "█" : "░");
        }
        bar.append("]");
        return bar.toString();
    }

    private String escapeHtml(String text) {
        if (text == null) return "";
        return text.replace("&", "&amp;").
                replace("<", "&lt;")
                .replace(">", "&gt;");
    }
}
