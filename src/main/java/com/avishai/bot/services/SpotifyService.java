package com.avishai.bot.services;

import com.avishai.bot.config.Config;
import com.avishai.bot.models.spotify.SpotiSyncState;
import com.avishai.bot.models.spotify.SpotifyResponses;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

@Slf4j
public class SpotifyService {
    private static final Pattern PLAYLIST_ID_PATTERN = Pattern.compile("playlist/([a-zA-Z0-9]+)");
    private final NextcloudService nextcloudService;
    private final AtomicBoolean isSyncing = new AtomicBoolean(false);
    private final AtomicBoolean abortFlag = new AtomicBoolean(false);
    private final Set<Process> activeProcesses = ConcurrentHashMap.newKeySet();

    private final HttpClient httpClient;
    private final ObjectMapper mapper;
    private final ExecutorService downloadPool;
    private long lastUiUpdateTime = 0;

    public SpotifyService(NextcloudService nextcloudService) {
        this.nextcloudService = nextcloudService;
        this.httpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(15)).build();
        this.mapper = new ObjectMapper();
        this.downloadPool = Executors.newFixedThreadPool(Config.SPOTIFY_DOWNLOAD_THREADS);
        Runtime.getRuntime().addShutdownHook(new Thread(this::abortSync));
    }

    public boolean isBusy() {
        return isSyncing.get();
    }

    public void abortSync() {
        if (isSyncing.get()) {
            log.warn("Abort signal received! Terminating all active yt-dlp processes...");
            abortFlag.set(true);
            activeProcesses.forEach(Process::destroyForcibly);
        }
    }

    public void runSync(Consumer<SpotiSyncState> onStateUpdate) {
        if (!isSyncing.compareAndSet(false, true)) return;

        abortFlag.set(false);
        var state = new SpotiSyncState();
        state.setTotalPlaylists(Config.SPOTIFY_PLAYLISTS.size());

        try {
            for (int i = 0; i < Config.SPOTIFY_PLAYLISTS.size(); i++) {
                if (abortFlag.get()) break;

                Config.SpotifyTarget target = Config.SPOTIFY_PLAYLISTS.get(i);
                log.info("=== Starting Sync for Folder: {} ===", target.folderName());

                state.setCurrentPlaylistNum(i + 1);
                state.getCurrentTrackName().set("Fetching metadata from HTML...");
                broadcastState(state, onStateUpdate, true);

                processPlaylist(target, state, onStateUpdate);
            }

            if (abortFlag.get()) {
                state.getGlobalStatus().set("Aborted");
                state.getCurrentTrackName().set("Process forcibly terminated.");
            } else {
                state.getGlobalStatus().set("Completed");
                state.getCurrentTrackName().set("All playlists synchronized.");
            }
        } catch (Exception e) {
            log.error("Critical Spotify execution error", e);
            state.getGlobalStatus().set("Critical Error");
            state.getCurrentTrackName().set(e.getMessage());
        } finally {
            state.getActive().set(false);
            broadcastState(state, onStateUpdate, true);
            isSyncing.set(false);

            if (!abortFlag.get() && "Completed".equals(state.getGlobalStatus().get())) {
                log.info("Spotify sync completed. Triggering automatic Nextcloud index scan for music folder...");
                try {
                    Path musicRootPath = Paths.get(NextcloudService.ROOT_PATH_STR, "Avishai/files/Music");
                    var scanResult = nextcloudService.runOccScan(musicRootPath);
                    log.info("Nextcloud auto-index finished with exit code {}: {}", scanResult.exitCode(), scanResult.output());
                } catch (Exception e) {
                    log.error("Failed to execute automatic Nextcloud index scan after Spotify sync", e);
                }
            }
        }
    }

    @SneakyThrows
    private void processPlaylist(Config.SpotifyTarget target, SpotiSyncState state, Consumer<SpotiSyncState> onUiUpdate) {
        String playlistId = extractPlaylistId(target.link());
        if (playlistId.isEmpty()) {
            throw new IllegalArgumentException("Invalid Spotify link configured for folder: " + target.folderName());
        }

        String url = "https://open.spotify.com/embed/playlist/" + playlistId;
        var request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .GET()
                .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64)")
                .header("Accept-Language", "en-US,en;q=0.9")
                .build();

        var response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() != 200) {
            throw new IllegalStateException("Failed to load playlist HTML. HTTP " + response.statusCode());
        }

        String html = response.body();
        String jsonPayload = extractJsonPayload(html);
        if (jsonPayload == null) {
            throw new IllegalStateException("Regex failed: Could not locate JSON metadata inside the HTML. Ensure the playlist is public.");
        }

        JsonNode root = mapper.readTree(jsonPayload);

        var targetDir = Paths.get(Config.MUSIC_STORAGE_ROOT, target.folderName());
        Files.createDirectories(targetDir);

        state.setCurrentPlaylistName(target.folderName());
        state.getTracksProcessedInCurrent().set(0);

        List<SpotifyResponses.Track> allTracks = new ArrayList<>();
        findTracksRecursively(root, allTracks);

        var uniqueTracks = allTracks.stream().distinct().toList();
        log.info("[{}] Extracted {} unique tracks from HTML.", target.folderName(), uniqueTracks.size());

        if (uniqueTracks.isEmpty()) {
            throw new IllegalStateException("JSON Parser found 0 tracks. The link might be broken or the playlist is empty.");
        }

        var existingFiles = getExistingFiles(targetDir);
        List<SpotifyResponses.Track> missingTracks = new ArrayList<>();

        for (var track : uniqueTracks) {
            String fileName = generateSafeFileName(track) + ".m4a";
            if (existingFiles.contains(fileName)) {
                log.info("[{}] Skipped (Already exists): {}", target.folderName(), fileName);
            } else {
                missingTracks.add(track);
            }
        }

        log.info("[{}] Directory holds {} total files. {} missing tracks queued for download.",
                target.folderName(), existingFiles.size(), missingTracks.size());

        state.setTracksInCurrentPlaylist(missingTracks.size());
        state.addSkipped(uniqueTracks.size() - missingTracks.size());
        broadcastState(state, onUiUpdate, true);

        List<CompletableFuture<Void>> tasks = missingTracks.stream()
                .map(track -> CompletableFuture.runAsync(() -> downloadTrack(track, targetDir, state, onUiUpdate), downloadPool))
                .toList();

        try {
            CompletableFuture.allOf(tasks.toArray(new CompletableFuture[0])).join();
        } catch (CompletionException e) {
            if (abortFlag.get()) log.info("Sync interrupted via abort flag.");
            else throw e;
        }
    }

    private String extractPlaylistId(String input) {
        if (input == null || input.isBlank()) return "";
        Matcher matcher = PLAYLIST_ID_PATTERN.matcher(input);
        if (matcher.find()) {
            return matcher.group(1);
        }
        return input.trim();
    }

    private String extractJsonPayload(String html) {
        var nextData = Pattern.compile("<script id=\"__NEXT_DATA__\" type=\"application/json\">(.*?)</script>").matcher(html);
        if (nextData.find()) return nextData.group(1);

        var initialState = Pattern.compile("<script id=\"initial-state\" type=\"text/plain\">(.*?)</script>").matcher(html);
        if (initialState.find()) return new String(java.util.Base64.getDecoder().decode(initialState.group(1)));

        return null;
    }

    private void findTracksRecursively(JsonNode node, List<SpotifyResponses.Track> tracks) {
        if (node.isObject()) {
            // Schema 1: Standard Format
            if (node.has("type") && "track".equals(node.get("type").asText()) && node.has("name") && node.has("artists")) {
                String name = node.get("name").asText();
                List<SpotifyResponses.Artist> artists = new ArrayList<>();
                node.get("artists").forEach(a -> {
                    if (a.has("name")) artists.add(new SpotifyResponses.Artist(a.get("name").asText()));
                });

                SpotifyResponses.Album albumObj = null;
                if (node.has("album")) {
                    JsonNode albumNode = node.get("album");
                    String albumName = albumNode.has("name") ? albumNode.get("name").asText() : "";
                    String releaseDate = albumNode.has("release_date") ? albumNode.get("release_date").asText() : "";
                    albumObj = new SpotifyResponses.Album(albumName, releaseDate);
                }

                tracks.add(new SpotifyResponses.Track(name, artists, albumObj));
                return;
            }
            // Schema 2: Embed Widget Fallback
            else if (node.has("title") && node.has("subtitle") && node.has("uri") && node.get("uri").asText().contains("track")) {
                String name = node.get("title").asText();
                String artist = node.get("subtitle").asText();
                tracks.add(new SpotifyResponses.Track(name, List.of(new SpotifyResponses.Artist(artist)), null));
                return;
            }
        }

        if (node.isObject() || node.isArray()) {
            node.elements().forEachRemaining(child -> findTracksRecursively(child, tracks));
        }
    }

    @SneakyThrows
    private void downloadTrack(SpotifyResponses.Track track, Path targetDir, SpotiSyncState state, Consumer<SpotiSyncState> onUiUpdate) {
        if (abortFlag.get()) return;

        var artist = track.artists().isEmpty() ? "Unknown" : track.artists().get(0).name().replace("\"", "");
        var title = track.name().replace("\"", "");
        var outputPath = targetDir.resolve(generateSafeFileName(track) + ".m4a");

        // Metadata extraction
        var albumName = (track.album() != null && track.album().name() != null && !track.album().name().isEmpty())
                ? track.album().name().replace("\"", "")
                : title; // Fallback album to the single title if Spotify doesn't provide one

        var releaseYear = "";
        if (track.album() != null && track.album().releaseDate() != null && track.album().releaseDate().length() >= 4) {
            releaseYear = track.album().releaseDate().substring(0, 4);
        }

        // Build exact ffmpeg metadata string
        String ffmpegArgs = String.format("ffmpeg:-metadata title=\"%s\" -metadata artist=\"%s\" -metadata album=\"%s\"", title, artist, albumName);
        if (!releaseYear.isEmpty()) {
            ffmpegArgs += String.format(" -metadata date=\"%s\"", releaseYear);
        }

        // Strict search using literal quotes
        String searchQuery = String.format("ytsearch1:\"%s\" \"%s\" audio", artist, title);

        var command = List.of(
                "yt-dlp",
                "-f", "ba/b",
                "--extract-audio", "--audio-format", "m4a", "--audio-quality", "0",
                "--output", outputPath.toString(),
                // NOTE: --embed-metadata is intentionally REMOVED here
                "--postprocessor-args", ffmpegArgs,
                searchQuery
        );

        Path errorLog = Files.createTempFile("ytdlp-err-", ".log");

        try {
            var pb = new ProcessBuilder(command)
                    .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                    .redirectError(errorLog.toFile());

            var process = pb.start();
            activeProcesses.add(process);
            int exitCode = process.waitFor();
            activeProcesses.remove(process);

            if (exitCode == 0) {
                state.markTrackSuccess(title);
            } else if (!abortFlag.get()) {
                String errorDetails = Files.readString(errorLog);
                log.error("[yt-dlp] Failed for '{} - {}'. Exit Code: {}\nError Output:\n{}", artist, title, exitCode, errorDetails.trim());
                state.markTrackFailed(title);
            }
        } catch (java.io.IOException e) {
            log.error("[yt-dlp] CRITICAL: Command failed to start. Error: {}", e.getMessage());
            state.markTrackFailed(title);
        } finally {
            Files.deleteIfExists(errorLog);
            broadcastState(state, onUiUpdate, false);
        }
    }

    @SneakyThrows
    private Set<String> getExistingFiles(Path targetDir) {
        try (Stream<Path> stream = Files.list(targetDir)) {
            return stream.filter(Files::isRegularFile)
                    .map(p -> p.getFileName().toString())
                    .collect(Collectors.toSet());
        }
    }

    private String generateSafeFileName(SpotifyResponses.Track track) {
        var artist = track.artists().isEmpty() ? "Unknown" : track.artists().get(0).name();
        return (artist + " - " + track.name()).replaceAll("[\\\\/:*?\"<>|]", "_");
    }

    private void broadcastState(SpotiSyncState state, Consumer<SpotiSyncState> onUiUpdate, boolean force) {
        long now = System.currentTimeMillis();
        if (force || now - lastUiUpdateTime > Config.TELEGRAM_UPDATE_INTERVAL_MS) {
            onUiUpdate.accept(state);
            lastUiUpdateTime = now;
        }
    }
}
