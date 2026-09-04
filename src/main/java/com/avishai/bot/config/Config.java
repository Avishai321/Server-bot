package com.avishai.bot.config;

import lombok.experimental.UtilityClass;

@UtilityClass
public class Config {
    public static final String BOT_TOKEN = System.getenv("BOT_TOKEN");
    public static final String BOT_USERNAME = System.getenv("BOT_USERNAME");

    public static final long AUTHORIZED_CHAT_ID = Long.parseLong(
            System.getenv().getOrDefault("AUTHORIZED_CHAT_ID", "0"));

    public static final String SPOTIFY_BACKUP_SCRIPT_PATH = "/home/avishai/scripts/sync_music.sh";
    public static final String PROJECT_PATH = "/home/avishai/projects/Server-bot";

    public static final long TELEGRAM_UPDATE_INTERVAL_MS = 2000;
}
