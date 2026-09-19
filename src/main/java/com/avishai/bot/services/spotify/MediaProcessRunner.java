package com.avishai.bot.services.spotify;

import com.avishai.bot.models.spotify.SpotifyResponses;
import lombok.extern.slf4j.Slf4j;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

@Slf4j
public class MediaProcessRunner {
    private final Set<Process> activeProcesses = ConcurrentHashMap.newKeySet();

    public static String cleanMetadataString(String input) {
        return input == null
                ? ""
                : input.replace("\"", "");
    }

    public void abortAll() {
        activeProcesses.forEach(Process::destroyForcibly);
    }

    public boolean executeYtDlp(
            String artist,
            String title,
            Path tempAudio,
            Path errorLog
    ) throws Exception {
        String primaryArtist = artist.split(",")[0].trim();

        String safeArtist = primaryArtist
                .replaceAll("[^a-zA-Z0-9\\p{IsHebrew}\\s]", " ")
                .replaceAll("\\s+", " ")
                .trim();

        String safeTitle = title
                .replaceAll("[^a-zA-Z0-9\\p{IsHebrew}\\s]", " ")
                .replaceAll("\\s+", " ")
                .trim();

        // Attempt 1: Artist + Title
        String primaryQuery = String.format("ytsearch1:%s %s audio", safeArtist, safeTitle)
                .replaceAll("\\s+", " ");

        boolean success = runYtDlpProcess(primaryQuery, tempAudio, errorLog);

        // Attempt 2: Fallback to Title-only if Attempt 1 found 0 results
        if (!success) {
            log.warn("[yt-dlp] Primary search yielded 0 files for '{} - {}'. " +
                            "Falling back to Title-only search.",
                    artist, title
            );
            Files.deleteIfExists(tempAudio);
            String fallbackQuery = String.format("ytsearch1:%s audio", safeTitle)
                    .replaceAll("\\s+", " ");
            success = runYtDlpProcess(fallbackQuery, tempAudio, errorLog);
        }

        return success;
    }

    private boolean runManagedProcess(List<String> command,
                                      Path errorLog,
                                      long timeout,
                                      String processType,
                                      String identifier) throws Exception {
        ProcessBuilder pb = new ProcessBuilder(command)
                .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                .redirectError(errorLog.toFile());

        setupProcessEnvironment(pb);

        Process process = pb.start();
        activeProcesses.add(process);

        try {
            boolean finished = process.waitFor(timeout, TimeUnit.MINUTES);
            if (!finished) {
                log.error("[{}] Timeout ({} {}) for '{}'. Process killed.",
                        processType, timeout, TimeUnit.MINUTES.name().toLowerCase(), identifier);
                return false;
            }
            return process.exitValue() == 0;
        } finally {
            activeProcesses.remove(process);
            if (process.isAlive()) process.destroyForcibly();
        }
    }

    private boolean runYtDlpProcess(String searchQuery,
                                    Path tempAudio,
                                    Path errorLog) throws Exception {
        String userHome = System.getProperty("user.home");
        String denoPath = userHome + "/.deno/bin/deno";

        List<String> command = new ArrayList<>(List.of(
                "yt-dlp",
                "--js-runtimes", "deno:" + denoPath,
                "-f", "ba/b",
                "--extract-audio",
                "--audio-format", "m4a",
                "--audio-quality", "0",
                "--output", tempAudio.toString(),
                searchQuery
        ));

        boolean success = runManagedProcess(
                command,
                errorLog,
                15,
                "yt-dlp",
                searchQuery
        );

        return success
                && Files.exists(tempAudio)
                && Files.size(tempAudio) > 0;
    }

    public boolean executeFfmpeg(
            SpotifyResponses.Track track,
            Path tempAudio,
            Path coverPath,
            Path finalOutputPath,
            boolean hasCover,
            String title,
            String artist,
            ItunesClient.ItunesMetadata itunesData,
            String lyrics,
            Path errorLog
    ) throws Exception {

        String albumName = getCleanAlbumName(track, title);
        String releaseYear = getReleaseYear(track);
        if (releaseYear.isEmpty() && itunesData.releaseYear() != null) {
            releaseYear = itunesData.releaseYear();
        }

        List<String> command = new ArrayList<>(List.of(
                "ffmpeg",
                "-hide_banner",
                "-loglevel", "error",
                "-y",
                "-i", tempAudio.toString()
        ));

        if (hasCover
                && coverPath != null
                && Files.exists(coverPath)
                && Files.size(coverPath) > 0) {
            command.addAll(List.of(
                    "-i", coverPath.toString(),
                    "-map", "0:a",
                    "-map", "1:v",
                    "-c:a", "copy",
                    "-c:v", "mjpeg",
                    "-disposition:v", "attached_pic"
            ));
        } else command.addAll(List.of("-c", "copy"));

        appendMetadata(command, "title", title);
        appendMetadata(command, "artist", artist);
        appendMetadata(command, "album_artist", artist);
        appendMetadata(command, "album", albumName);
        appendMetadata(command, "date", releaseYear);
        appendMetadata(command, "genre", itunesData.genre());
        appendMetadata(command, "lyrics", lyrics);

        if (itunesData.trackNumber() != null && itunesData.trackCount() != null) {
            appendMetadata(command,
                    "track",
                    itunesData.trackNumber() + "/" + itunesData.trackCount()
            );
        }
        if (itunesData.discNumber() != null && itunesData.discCount() != null) {
            appendMetadata(command,
                    "disc",
                    itunesData.discNumber() + "/" + itunesData.discCount()
            );
        }

        command.add(finalOutputPath.toString());

        String identifier = artist + " - " + title;
        return runManagedProcess(
                command,
                errorLog,
                5,
                "ffmpeg",
                identifier
        );
    }

    private void appendMetadata(List<String> command, String key, String value) {
        if (value != null && !value.isBlank()) {
            command.add("-metadata");
            command.add(key + "=" + value);
        }
    }

    private void setupProcessEnvironment(ProcessBuilder pb) {
        var env = pb.environment();
        String sysPath = env.getOrDefault("PATH", "");
        env.put(
                "PATH", "/usr/local/bin:/usr/bin:/bin"
                        + (sysPath.isEmpty() ? "" : ":" + sysPath)
        );
    }

    private String getCleanAlbumName(
            SpotifyResponses.Track track,
            String fallbackTitle
    ) {
        if (track.album() != null
                && track.album().name() != null
                && !track.album().name().isEmpty()) {
            return cleanMetadataString(track.album().name());
        }
        return fallbackTitle;
    }

    private String getReleaseYear(SpotifyResponses.Track track) {
        return track.album() != null
                && track.album().releaseDate() != null
                && track.album().releaseDate().length() >= 4
                ? track.album().releaseDate().substring(0, 4)
                : "";
    }
}
