package com.avishai.bot.services.spotify;

import com.avishai.bot.config.Config;
import com.avishai.bot.models.spotify.SpotiSyncState;
import com.avishai.bot.models.spotify.SpotifyResponses;
import com.avishai.bot.services.NextcloudService;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;

import java.net.http.HttpClient;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Duration;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import java.util.stream.Collectors;
import java.util.stream.Stream;

@Slf4j
public class SpotifyService {
    private final AtomicBoolean isSyncing = new AtomicBoolean(false);
    private final AtomicBoolean abortFlag = new AtomicBoolean(false);

    private final NextcloudService nextcloudService;
    private final ExecutorService downloadPool;

    private final SpotifyScraper scraper;
    private final ItunesClient itunesClient;
    private final LrcLibClient lrcLibClient;
    private final MediaProcessRunner processRunner;

    private long lastUiUpdateTime = 0;

    public SpotifyService(NextcloudService nextcloudService) {
        this.nextcloudService = nextcloudService;
        this.downloadPool = Executors.newFixedThreadPool(Config.SPOTIFY_DOWNLOAD_THREADS);

        HttpClient sharedClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(15))
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();
        ObjectMapper sharedMapper = new ObjectMapper();

        this.scraper = new SpotifyScraper(sharedClient, sharedMapper);
        this.itunesClient = new ItunesClient(sharedClient, sharedMapper);
        this.lrcLibClient = new LrcLibClient(sharedClient, sharedMapper);
        this.processRunner = new MediaProcessRunner();

        Runtime.getRuntime().addShutdownHook(new Thread(this::abortSync));
    }

    public boolean isBusy() {
        return isSyncing.get();
    }

    public void abortSync() {
        if (isSyncing.get()) {
            log.warn("Abort signal received! Terminating active processes...");
            abortFlag.set(true);
            processRunner.abortAll();
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

        List<SpotifyResponses.Track> uniqueTracks = scraper.extractTracks(target.link(), target.folderName())
                .stream().distinct().toList();

        log.info("[{}] Extracted {} unique tracks.", target.folderName(), uniqueTracks.size());
        if (uniqueTracks.isEmpty()) {
            throw new IllegalStateException("Parser found 0 tracks. Link may be broken.");
        }

        Path targetDir = Paths.get(Config.MUSIC_STORAGE_ROOT, target.folderName());
        Files.createDirectories(targetDir);
        state.setCurrentPlaylistName(target.folderName());
        state.getTracksProcessedInCurrent().set(0);

        Set<String> existingFiles = getExistingFiles(targetDir);
        List<SpotifyResponses.Track> missingTracks = uniqueTracks.stream()
                .filter(track -> !existingFiles.contains(generateSafeFileName(track) + ".m4a"))
                .toList();

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

    private void downloadTrack(SpotifyResponses.Track track,
                               Path targetDir,
                               SpotiSyncState state,
                               Consumer<SpotiSyncState> onUiUpdate) {
        if (abortFlag.get()) return;

        String artist = MediaProcessRunner.cleanMetadataString(
                track.artists().isEmpty() ? "Unknown" : track.artists().get(0).name()
        );
        String title = MediaProcessRunner.cleanMetadataString(track.name());
        Path finalOutputPath = targetDir.resolve(generateSafeFileName(track) + ".m4a");

        int maxRetries = 3;

        for (int attempt = 1; attempt <= maxRetries && !abortFlag.get(); attempt++) {
            Path tempAudio = null;
            Path tempCover = null;
            Path errorLog = null;

            try {
                tempAudio = Files.createTempFile("audio-", ".m4a");
                Files.deleteIfExists(tempAudio);
                tempCover = Files.createTempFile("cover-", ".jpg");
                errorLog = Files.createTempFile("ytdlp-err-", ".log");

                String baseFileName = generateSafeFileName(track);
                ItunesClient.ItunesMetadata itunesData = itunesClient.fetchItunesMetadata(artist, title);
                boolean hasCover = itunesClient.downloadImage(itunesData.coverUrl(), tempCover);
                String lyrics = lrcLibClient.fetchLyrics(artist, title, targetDir, baseFileName);

                boolean audioDownloaded = processRunner.executeYtDlp(artist, title, tempAudio, errorLog);
                if (!audioDownloaded) {
                    String errorDetails = Files.readString(errorLog);
                    log.warn("[yt-dlp] failed for {}. Output:\n{}", title, errorDetails.trim());
                    if (errorDetails.contains("ERROR: No video results")) {
                        log.error("Hard failure for '{} - {}'. Aborting.", artist, title);
                        state.markTrackFailed(title);
                        return;
                    }
                } else {
                    boolean metadataEmbedded = processRunner.executeFfmpeg(
                            track, tempAudio, tempCover, finalOutputPath, hasCover,
                            title, artist, itunesData, lyrics, errorLog
                    );

                    if (metadataEmbedded) {
                        log.info("Downloaded: {}", title);
                        state.markTrackSuccess(title);
                        return;
                    }
                    String errorDetails = Files.readString(errorLog);
                    log.warn("[ffmpeg] failed for '{} - {}'. Output:\n{}", artist, title, errorDetails.trim());
                }

                if (attempt == maxRetries) {
                    log.error("Track permanently failed after {} attempts: '{} - {}'", maxRetries, artist, title);
                    state.markTrackFailed(title);
                    return;
                }

            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                log.warn("Retry interrupted for '{} - {}'", artist, title);
                return;
            } catch (Exception e) {
                log.warn("Exception for '{} - {}'. Error: {}", artist, title, e.getMessage());
                if (attempt == maxRetries) {
                    state.markTrackFailed(title);
                    return;
                }
            } finally {
                cleanupTempFile(tempAudio);
                cleanupTempFile(tempCover);
                cleanupTempFile(errorLog);
                broadcastState(state, onUiUpdate, false);
            }
        }
    }

    private void executeNextcloudScan() {
        log.info("Spotify sync completed. Triggering automatic Nextcloud index scan...");
        try {
            Path musicRootPath = Paths.get(Config.MUSIC_STORAGE_ROOT);
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
        String artist = track.artists().isEmpty()
                ? "Unknown"
                : track.artists().get(0).name();
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
