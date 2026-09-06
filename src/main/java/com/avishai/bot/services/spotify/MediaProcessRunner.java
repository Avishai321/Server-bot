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
        return input == null ? "" : input.replace("\"", "");
    }

    public void abortAll() {
        activeProcesses.forEach(Process::destroyForcibly);
    }

    public boolean executeYtDlp(String artist, String title, Path tempAudio, Path errorLog) throws Exception {
        String searchQuery = String.format("ytsearch1:\"%s\" \"%s\" audio", artist, title);
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

        var pb = new ProcessBuilder(command)
                .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                .redirectError(errorLog.toFile());
        setupProcessEnvironment(pb);

        Process process = pb.start();
        activeProcesses.add(process);
        boolean finished = process.waitFor(15, TimeUnit.MINUTES);
        activeProcesses.remove(process);

        if (!finished) {
            process.destroyForcibly();
            log.error("[yt-dlp] Timeout (15m) for '{} - {}'. Process killed.", artist, title);
            return false;
        }
        return process.exitValue() == 0;
    }

    // ... inside MediaProcessRunner.java
    public boolean executeFfmpeg(SpotifyResponses.Track track,
                                 Path tempAudio,
                                 Path coverPath,
                                 Path finalOutputPath,
                                 boolean hasCover,
                                 String title,
                                 String artist,
                                 ItunesClient.ItunesMetadata itunesData,
                                 String lyrics,
                                 Path errorLog) throws Exception {

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

        if (hasCover && coverPath != null && Files.exists(coverPath) && Files.size(coverPath) > 0) {
            command.addAll(List.of(
                    "-i", coverPath.toString(),
                    "-map", "0:a",
                    "-map", "1:v",
                    "-c:a", "copy",
                    "-c:v", "mjpeg",
                    "-disposition:v", "attached_pic"
            ));
        } else {
            command.addAll(List.of("-c", "copy"));
        }

        appendMetadata(command, "title", title);
        appendMetadata(command, "artist", artist);
        appendMetadata(command, "album_artist", artist);
        appendMetadata(command, "album", albumName);
        appendMetadata(command, "date", releaseYear);
        appendMetadata(command, "genre", itunesData.genre());
        appendMetadata(command, "lyrics", lyrics);

        if (itunesData.trackNumber() != null && itunesData.trackCount() != null) {
            appendMetadata(command, "track", itunesData.trackNumber() + "/" + itunesData.trackCount());
        }
        if (itunesData.discNumber() != null && itunesData.discCount() != null) {
            appendMetadata(command, "disc", itunesData.discNumber() + "/" + itunesData.discCount());
        }

        command.add(finalOutputPath.toString());

        ProcessBuilder pb = new ProcessBuilder(command)
                .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                .redirectError(errorLog.toFile());
        setupProcessEnvironment(pb);

        Process process = pb.start();
        activeProcesses.add(process);
        boolean finished = process.waitFor(5, TimeUnit.MINUTES);
        activeProcesses.remove(process);

        if (!finished) {
            process.destroyForcibly();
            log.error("[ffmpeg] Timeout (5m) for '{} - {}'. Process killed.", artist, title);
            return false;
        }
        return process.exitValue() == 0;
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
        env.put("PATH", "/usr/local/bin:/usr/bin:/bin" + (sysPath.isEmpty() ? "" : ":" + sysPath));
    }

    private String getCleanAlbumName(SpotifyResponses.Track track, String fallbackTitle) {
        if (track.album() != null && track.album().name() != null && !track.album().name().isEmpty()) {
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
