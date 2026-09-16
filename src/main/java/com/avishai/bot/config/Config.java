package com.avishai.bot.config;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.experimental.UtilityClass;
import lombok.extern.slf4j.Slf4j;

import java.io.File;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;

@Slf4j
@UtilityClass
public class Config {
    public static final String BOT_TOKEN = System.getenv("BOT_TOKEN");
    public static final String BOT_USERNAME = System.getenv("BOT_USERNAME");

    public static final String AUTHORIZED_CHAT_ID = System.getenv("AUTHORIZED_CHAT_ID");

    public static final String PROJECT_PATH = System.getenv("PROJECT_PATH");
    public static final String MUSIC_STORAGE_ROOT = System.getenv("MUSIC_STORAGE_ROOT");
    public static final String CONFIG_DIR = System.getenv().getOrDefault("CONFIG_DIR", ".");

    public static final long TELEGRAM_UPDATE_INTERVAL_MS = 2000;
    public static final int SPOTIFY_DOWNLOAD_THREADS = 4;

    public static final List<SpotifyTarget> SPOTIFY_PLAYLISTS = loadPlaylist();

    private static List<SpotifyTarget> loadPlaylist() {
        try {
            ObjectMapper mapper = new ObjectMapper();
            Path configPath = Paths.get(CONFIG_DIR, "playlists.json");
            File file = configPath.toFile();

            if (!file.exists()) {
                log.error("playlists.json not found at: {}", configPath.toAbsolutePath());
                return List.of();
            }
            return mapper.readValue(file, new TypeReference<>() {});
        } catch (Exception e) {
            log.error("Failed to parse playlists.json", e);
            return List.of();
        }
    }

    public record SpotifyTarget(String link, String folderName) {
    }
}
