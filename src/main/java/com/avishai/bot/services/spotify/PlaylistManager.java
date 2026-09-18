package com.avishai.bot.services.spotify;

import com.avishai.bot.config.Config;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;

import java.io.File;
import java.util.List;

@Slf4j
public class PlaylistManager {
    private static final PlaylistManager INSTANCE = new PlaylistManager();
    private final ObjectMapper mapper = new ObjectMapper();

    private volatile List<SpotifyTarget> playlists = List.of();
    private volatile long lastModifiedTime = 0;

    private PlaylistManager() {
        reloadIfNeeded();
    }

    public static PlaylistManager getInstance() {
        return INSTANCE;
    }

    public List<SpotifyTarget> getPlaylists() {
        reloadIfNeeded();
        return playlists;
    }

    private synchronized void reloadIfNeeded() {
        File file = new File(Config.PLAYLIST_FILE_PATH);

        if (!file.exists()) {
            if (!playlists.isEmpty()) {
                log.error("playlists.json not found at: {}. " +
                        "Clearing playlists.", file.getAbsolutePath()
                );
                this.playlists = List.of();
                this.lastModifiedTime = 0;
            }
            return;
        }

        long currentModifiedTime = file.lastModified();

        if (currentModifiedTime > lastModifiedTime) {
            try {
                this.playlists = mapper.readValue(file, new TypeReference<>() {});
                this.lastModifiedTime = currentModifiedTime;
                log.info("Playlists reloaded from disk. Count: {}", this.playlists.size());
            } catch (Exception e) {
                log.error("Failed to parse playlists.json. Retaining previous state.", e);
            }
        }
    }

    public record SpotifyTarget(String link, String folderName) {}
}
