package com.avishai.bot.config;

import lombok.experimental.UtilityClass;

@UtilityClass
public class Config {
    public static final String BOT_TOKEN = System.getenv("BOT_TOKEN");
    public static final String BOT_USERNAME = System.getenv("BOT_USERNAME");

    public static final String AUTHORIZED_CHAT_ID = System.getenv("AUTHORIZED_CHAT_ID");

    public static final String PROJECT_PATH = System.getenv("PROJECT_PATH");
    public static final String MUSIC_STORAGE_ROOT = System.getenv("MUSIC_STORAGE_ROOT");

    public static final String TAILSCALE_IP = System.getenv("TAILSCALE_IP");
    public static final int WEB_PORT = 8081;

    public static final int GLOBAL_EXECUTOR_THREADS = 10;
    public static final long TELEGRAM_UPDATE_INTERVAL_MS = 2000;
    public static final int SPOTIFY_DOWNLOAD_THREADS = 4;
}
