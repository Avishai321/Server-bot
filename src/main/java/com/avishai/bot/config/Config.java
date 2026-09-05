package com.avishai.bot.config;

import lombok.experimental.UtilityClass;

import java.util.List;

@UtilityClass
public class Config {
    public static final String BOT_TOKEN = System.getenv("BOT_TOKEN");
    public static final String BOT_USERNAME = System.getenv("BOT_USERNAME");
    public static final long AUTHORIZED_CHAT_ID = Long.parseLong(
            System.getenv().getOrDefault("AUTHORIZED_CHAT_ID", "0"));

    public static final String PROJECT_PATH = "/home/avishai/projects/Server-bot";
    public static final long TELEGRAM_UPDATE_INTERVAL_MS = 2000;

    // --- SPOTIFY --- //
    public static final String MUSIC_STORAGE_ROOT = "/mnt/d/data/Avishai/files/Music";
    public static final int SPOTIFY_DOWNLOAD_THREADS = 4;

    public static final List<SpotifyTarget> SPOTIFY_PLAYLISTS = List.of(
            new SpotifyTarget(
                    "https://open.spotify.com/playlist/6NMF89lNrvDi375IuUxbOI?si=5d24b75e3dd7440a",
                    "Liked Songs"
            ),
            new SpotifyTarget(
                    "https://open.spotify.com/playlist/5WGvY0rYu8VlyqkqV13Vv2?si=8fc6e7d6eb0743d5",
                    "Mizrahit"
            ),
            new SpotifyTarget(
                    "https://open.spotify.com/playlist/26FFzgo75zf8ZiMgwAMsIo?si=be3ed54509df4bd3",
                    "Mushlamim"
            ),
            new SpotifyTarget(
                    "https://open.spotify.com/playlist/4TPKjTM3TW33HM5n4PkCqZ?si=4e383e75fdb7449f",
                    "Classic"
            ),
            new SpotifyTarget(
                    "https://open.spotify.com/playlist/5rYO0emP4oZHykoPqB5B9S?si=fc978fce32f547db",
                    "Rap"
            ),
            new SpotifyTarget(
                    "https://open.spotify.com/playlist/2C2f9dcrNnKgQ7KWP9MWSn?si=47bc0f90b3714014",
                    "Israeli Classic"
            ),
            new SpotifyTarget(
                    "https://open.spotify.com/playlist/1UbpkBuk9zi21xor0qH7jl?si=a27349e09b254ff4",
                    "Art"
            ),
            new SpotifyTarget(
                    "https://open.spotify.com/playlist/53FeVDMuJUZmshUuq1RUhU?si=b5934aa8d4a14482",
                    "Once Loved"
            )
    );

    public record SpotifyTarget(String link, String folderName) {
    }
}
