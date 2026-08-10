package com.avishai.bot.core;

public class Config {
    public static final String BOT_TOKEN = System.getenv("BOT_TOKEN");
    public static final String BOT_USERNAME = System.getenv("BOT_USERNAME");

    public static final String SPOTIFY_BACKUP_SCRIPT_PATH = System.getenv(
            "SPOTIFY_BACKUP_SCRIPT_PATH");

    public static final String PROJECT_PATH = System.getenv("PROJECT_PATH");

    public static final long AUTHORIZED_CHAT_ID = Long.parseLong(
            System.getenv().getOrDefault("AUTHORIZED_CHAT_ID", "0"));

    public static final long TELEGRAM_UPDATE_INTERVAL_MS = 2000;
}
