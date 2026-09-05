package com.avishai.bot.services;

import com.avishai.bot.config.Config;
import com.avishai.bot.models.spotify.SpotiSyncState;
import com.avishai.bot.models.spotify.SpotifyResponses;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
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

import static java.util.Base64.getDecoder;

@Slf4j
public class SpotifyService {
    private static final Pattern PLAYLIST_ID_PATTERN =
            Pattern.compile("playlist/([a-zA-Z0-9]+)");
    private static final Pattern NEXT_DATA_PATTERN =
            Pattern.compile("<script id=\"__NEXT_DATA__\" type=\"application/json\">(.*?)</script>");
    private static final Pattern INITIAL_STATE_PATTERN =
            Pattern.compile("<script id=\"initial-state\" type=\"text/plain\">(.*?)</script>");

    private final AtomicBoolean isSyncing = new AtomicBoolean(false);
    private final AtomicBoolean abortFlag = new AtomicBoolean(false);
    private final Set<Process> activeProcesses = ConcurrentHashMap.newKeySet();

    private final NextcloudService nextcloudService;
    private final HttpClient httpClient;
    private final ObjectMapper mapper;
    private final ExecutorService downloadPool;
    private long lastUiUpdateTime = 0;

    public SpotifyService(NextcloudService nextcloudService) {
        this.nextcloudService = nextcloudService;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(15))
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();
        this.mapper = new ObjectMapper();
        this.downloadPool = Executors.newFixedThreadPool(Config.SPOTIFY_DOWNLOAD_THREADS);

        Runtime.getRuntime().addShutdownHook(new Thread(this::abortSync));
    }

    public boolean isBusy() {
        return isSyncing.get();
    }

    public void abortSync() {
        if (isSyncing.get()) {
            log.warn("Abort signal received! Terminating active processes...");
            abortFlag.set(true);
            activeProcesses.forEach(Process::destroyForcibly);
        }
    }

    public void runSync(Consumer<SpotiSyncState> onStateUpdate) {
        if (!isSyncing.compareAndSet(false, true)) return;

        abortFlag.set(false);
        SpotiSyncState state = new SpotiSyncState();
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
                executeNextcloudScan();
            }
        }
    }

    private void processPlaylist(Config.SpotifyTarget target,
                                 SpotiSyncState state,
                                 Consumer<SpotiSyncState> onUiUpdate) throws Exception {

        String playlistId = extractPlaylistId(target.link());
        if (playlistId.isEmpty()) {
            throw new IllegalArgumentException("Invalid Spotify link for: " + target.folderName());
        }

        String html = fetchPlaylistHtml(playlistId);
        String jsonPayload = extractJsonPayload(html);

        if (jsonPayload == null) {
            throw new IllegalStateException("Could not locate JSON metadata inside the HTML.");
        }

        JsonNode root = mapper.readTree(jsonPayload);
        Path targetDir = Paths.get(Config.MUSIC_STORAGE_ROOT, target.folderName());
        Files.createDirectories(targetDir);

        state.setCurrentPlaylistName(target.folderName());
        state.getTracksProcessedInCurrent().set(0);

        List<SpotifyResponses.Track> allTracks = new ArrayList<>();
        findTracksRecursively(root, allTracks);
        var uniqueTracks = allTracks.stream().distinct().toList();
        log.info("[{}] Extracted {} unique tracks.", target.folderName(), uniqueTracks.size());

        if (uniqueTracks.isEmpty()) {
            throw new IllegalStateException("Parser found 0 tracks. Link may be broken.");
        }

        Set<String> existingFiles = getExistingFiles(targetDir);
        List<SpotifyResponses.Track> missingTracks = new ArrayList<>();

        for (var track : uniqueTracks) {
            String fileName = generateSafeFileName(track) + ".m4a";
            if (existingFiles.contains(fileName)) {
                log.info("[{}] Skipped (Already exists): {}", target.folderName(), fileName);
            } else {
                missingTracks.add(track);
            }
        }

        log.info("[{}] Folder holds {} files. {} missing tracks queued.",
                target.folderName(), existingFiles.size(), missingTracks.size());

        state.setTracksInCurrentPlaylist(missingTracks.size());
        state.addSkipped(uniqueTracks.size() - missingTracks.size());
        broadcastState(state, onUiUpdate, true);

        List<CompletableFuture<Void>> tasks = missingTracks.stream()
                .map(track -> CompletableFuture.runAsync(() ->
                        downloadTrack(track, targetDir, state, onUiUpdate), downloadPool)
                ).toList();

        try {
            CompletableFuture.allOf(tasks.toArray(new CompletableFuture[0])).join();
        } catch (CompletionException e) {
            if (abortFlag.get()) log.info("Sync interrupted via abort flag.");
            else throw e;
        }
    }

    private String fetchPlaylistHtml(String playlistId) throws Exception {
        String url = "https://open.spotify.com/embed/playlist/" + playlistId;
        var request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .GET()
                .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64)")
                .header("Accept-Language", "en-US,en;q=0.9")
                .build();

        var response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() != 200) {
            throw new IllegalStateException("Failed to load HTML. HTTP " + response.statusCode());
        }
        return response.body();
    }

    private String extractPlaylistId(String input) {
        if (input == null || input.isBlank()) return "";
        Matcher matcher = PLAYLIST_ID_PATTERN.matcher(input);
        if (matcher.find()) return matcher.group(1);
        return input.trim();
    }

    private String extractJsonPayload(String html) {
        Matcher nextData = NEXT_DATA_PATTERN.matcher(html);
        if (nextData.find()) return nextData.group(1);

        Matcher initialState = INITIAL_STATE_PATTERN.matcher(html);
        if (initialState.find()) return new String(getDecoder().decode(initialState.group(1)));

        return null;
    }

    private void findTracksRecursively(JsonNode node, List<SpotifyResponses.Track> tracks) {
        if (node.isObject()) {
            boolean isStandardTrack = node.has("type")
                    && "track".equals(node.get("type").asText())
                    && node.has("name")
                    && node.has("artists");

            boolean isEmbedTrack = node.has("title")
                    && node.has("subtitle")
                    && node.has("uri")
                    && node.get("uri").asText().contains("track");

            if (isStandardTrack) {
                tracks.add(parseStandardTrack(node));
                return;
            } else if (isEmbedTrack) {
                tracks.add(parseEmbedTrack(node));
                return;
            }
        }

        if (node.isObject() || node.isArray()) {
            node.elements().forEachRemaining(child -> findTracksRecursively(child, tracks));
        }
    }

    private SpotifyResponses.Track parseStandardTrack(JsonNode node) {
        String name = node.get("name").asText();
        List<SpotifyResponses.Artist> artists = new ArrayList<>();

        node.get("artists").forEach(a -> {
            if (a.has("name")) {
                artists.add(new SpotifyResponses.Artist(a.get("name").asText()));
            }
        });

        SpotifyResponses.Album albumObj = null;
        if (node.has("album")) {
            JsonNode albumNode = node.get("album");
            String albumName = albumNode.has("name") ? albumNode.get("name").asText() : "";
            String releaseDate = albumNode.has("release_date")
                    ? albumNode.get("release_date").asText() : "";
            albumObj = new SpotifyResponses.Album(albumName, releaseDate);
        }

        return new SpotifyResponses.Track(name, artists, albumObj, "");
    }

    private SpotifyResponses.Track parseEmbedTrack(JsonNode node) {
        String name = node.get("title").asText();
        String artist = node.get("subtitle").asText();
        return new SpotifyResponses.Track(name, List.of(new SpotifyResponses.Artist(artist)), null, "");
    }

    private String fetchItunesCoverUrl(String artist, String title) {
        try {
            String query = artist + " " + title;
            String encodedQuery = URLEncoder.encode(query, StandardCharsets.UTF_8);
            String url = "https://itunes.apple.com/search?term=" + encodedQuery + "&entity=song&limit=1";

            HttpRequest req = HttpRequest.newBuilder(URI.create(url))
                    .GET()
                    .header("User-Agent", "Mozilla/5.0")
                    .build();

            HttpResponse<String> res = httpClient.send(req, HttpResponse.BodyHandlers.ofString());
            if (res.statusCode() == 200) {
                JsonNode root = mapper.readTree(res.body());
                if (root.has("resultCount") && root.get("resultCount").asInt() > 0) {
                    JsonNode results = root.get("results");
                    if (results.isArray() && !results.isEmpty()) {
                        String artworkUrl = results.get(0).get("artworkUrl100").asText();
                        return artworkUrl.replace("100x100bb.jpg", "600x600bb.jpg");
                    }
                }
            }
        } catch (Exception e) {
            log.warn("iTunes API failed for '{} - {}': {}", artist, title, e.getMessage());
        }
        return "";
    }

    private boolean downloadImage(String urlStr, Path targetPath) {
        if (urlStr == null || urlStr.isEmpty()) return false;
        try {
            var req = HttpRequest.newBuilder(URI.create(urlStr))
                    .GET()
                    .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64)")
                    .header("Accept", "image/*")
                    .build();

            var res = httpClient.send(req, HttpResponse.BodyHandlers.ofFile(targetPath));

            if (res.statusCode() != 200) return false;
            if (!Files.exists(targetPath) || Files.size(targetPath) == 0) return false;

            String contentType = res.headers().firstValue("Content-Type").orElse("").toLowerCase();
            return contentType.startsWith("image/");
        } catch (Exception e) {
            return false;
        }
    }

    private void setupProcessEnvironment(ProcessBuilder pb) {
        var env = pb.environment();
        String sysPath = env.getOrDefault("PATH", "");
        env.put("PATH", "/usr/local/bin:/usr/bin:/bin" + (sysPath.isEmpty() ? "" : ":" + sysPath));
    }

    private void downloadTrack(SpotifyResponses.Track track,
                               Path targetDir,
                               SpotiSyncState state,
                               Consumer<SpotiSyncState> onUiUpdate) {

        if (abortFlag.get()) return;

        String artist = cleanMetadataString(
                track.artists().isEmpty() ? "Unknown" : track.artists().get(0).name());
        String title = cleanMetadataString(track.name());
        Path finalOutputPath = targetDir.resolve(generateSafeFileName(track) + ".m4a");

        Path tempAudio = null;
        Path tempCover = null;
        Path errorLog = null;

        try {
            tempAudio = Files.createTempFile("audio-", ".m4a");
            Files.deleteIfExists(tempAudio);

            tempCover = Files.createTempFile("cover-", ".jpg");
            errorLog = Files.createTempFile("ytdlp-err-", ".log");

            // Query iTunes for the exact track cover
            String coverUrl = fetchItunesCoverUrl(artist, title);
            boolean hasCover = downloadImage(coverUrl, tempCover);

            boolean audioDownloaded = executeYtDlp(artist, title, tempAudio, errorLog);

            if (!audioDownloaded) {
                if (!abortFlag.get()) {
                    String errorDetails = Files.readString(errorLog);
                    log.error("[yt-dlp] Failed for '{} - {}'. Output:\n{}",
                            artist, title, errorDetails.trim());
                    state.markTrackFailed(title);
                }
                return;
            }

            boolean metadataEmbedded = executeFfmpeg(
                    track, tempAudio, tempCover, finalOutputPath, hasCover, title, artist, errorLog);

            if (metadataEmbedded) {
                state.markTrackSuccess(title);
            } else {
                state.markTrackFailed(title);
            }

        } catch (Exception e) {
            log.error("[Sync] CRITICAL failure for track '{} - {}'. Error: {}",
                    artist, title, e.getMessage());
            state.markTrackFailed(title);
        } finally {
            cleanupTempFile(tempAudio);
            cleanupTempFile(tempCover);
            cleanupTempFile(errorLog);
            broadcastState(state, onUiUpdate, false);
        }
    }

    private boolean executeYtDlp(String artist, String title, Path tempAudio, Path errorLog)
            throws Exception {

        String searchQuery = String.format("ytsearch1:\"%s\" \"%s\" audio", artist, title);

        List<String> command = new ArrayList<>(List.of(
                "yt-dlp",
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

    private boolean executeFfmpeg(SpotifyResponses.Track track,
                                  Path tempAudio,
                                  Path coverPath,
                                  Path finalOutputPath,
                                  boolean hasCover,
                                  String title,
                                  String artist,
                                  Path errorLog) throws Exception {

        String albumName = getCleanAlbumName(track, title);
        String releaseYear = getReleaseYear(track);

        List<String> command = new ArrayList<>(List.of(
                "ffmpeg",
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

        command.addAll(List.of(
                "-metadata", "title=" + title,
                "-metadata", "artist=" + artist,
                "-metadata", "album=" + albumName
        ));

        if (!releaseYear.isEmpty()) {
            command.addAll(List.of("-metadata", "date=" + releaseYear));
        }

        command.add(finalOutputPath.toString());

        var pb = new ProcessBuilder(command)
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

        if (process.exitValue() != 0) {
            String errorDetails = Files.readString(errorLog);
            log.error("[ffmpeg] Failed for '{} - {}'. Exit Code: {}\nOutput:\n{}",
                    artist, title, process.exitValue(), errorDetails.trim());
            return false;
        }

        return true;
    }

    private String getCleanAlbumName(SpotifyResponses.Track track, String fallbackTitle) {
        if (track.album() != null
                && track.album().name() != null
                && !track.album().name().isEmpty()) {
            return cleanMetadataString(track.album().name());
        }
        return fallbackTitle;
    }

    private String getReleaseYear(SpotifyResponses.Track track) {
        if (track.album() != null
                && track.album().releaseDate() != null
                && track.album().releaseDate().length() >= 4) {
            return track.album().releaseDate().substring(0, 4);
        }
        return "";
    }

    private String cleanMetadataString(String input) {
        return input == null ? "" : input.replace("\"", "");
    }

    private void executeNextcloudScan() {
        log.info("Spotify sync completed. Triggering automatic Nextcloud index scan...");
        try {
            Path musicRootPath = Paths.get(NextcloudService.ROOT_PATH_STR, "Avishai/files/Music");
            var scanResult = nextcloudService.runOccScan(musicRootPath);
            log.info("Nextcloud auto-index finished with exit code {}: \n{}",
                    scanResult.exitCode(), scanResult.output());
        } catch (Exception e) {
            log.error("Failed to execute automatic Nextcloud index scan", e);
        }
    }

    private void cleanupTempFile(Path tempFile) {
        if (tempFile != null) {
            try {
                Files.deleteIfExists(tempFile);
            } catch (Exception ignored) {
            }
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
        String artist = track.artists().isEmpty() ? "Unknown" : track.artists().get(0).name();
        String rawName = artist + " - " + track.name();
        return rawName.replaceAll("[\\\\/:*?\"<>|]", "_");
    }

    private void broadcastState(SpotiSyncState state, Consumer<SpotiSyncState> onUiUpdate, boolean force) {
        long now = System.currentTimeMillis();
        if (force || now - lastUiUpdateTime > Config.TELEGRAM_UPDATE_INTERVAL_MS) {
            onUiUpdate.accept(state);
            lastUiUpdateTime = now;
        }
    }
}
