package com.avishai.bot.config;

import lombok.experimental.UtilityClass;

import java.nio.file.Paths;

@UtilityClass
public class Config {
    public static final String BOT_TOKEN = System.getenv("BOT_TOKEN");
    public static final String BOT_USERNAME = System.getenv("BOT_USERNAME");

    public static final String AUTHORIZED_CHAT_ID = System.getenv("AUTHORIZED_CHAT_ID");

    public static final String PROJECT_PATH = System.getenv("PROJECT_PATH");
    public static final String MUSIC_STORAGE_ROOT = System.getenv("MUSIC_STORAGE_ROOT");
    public static final String CONFIG_DIR = System.getenv().getOrDefault("CONFIG_DIR", ".");
    public static final String PLAYLIST_FILE_PATH = Paths.get(CONFIG_DIR, "playlists.json").toString();

    public static final int GLOBAL_EXECUTOR_THREADS = 10;
    public static final long TELEGRAM_UPDATE_INTERVAL_MS = 2000;
    public static final int SPOTIFY_DOWNLOAD_THREADS = 4;
}
