package com.avishai.bot.services.spotify;

import com.avishai.bot.config.Config;
import com.avishai.bot.core.ManagedService;
import com.avishai.bot.models.spotify.SpotiSyncState;
import com.avishai.bot.models.spotify.SpotifyResponses;
import com.avishai.bot.services.NextcloudService;
import lombok.RequiredArgsConstructor;
import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import java.util.stream.Collectors;
import java.util.stream.Stream;

@Slf4j
@RequiredArgsConstructor
public class SpotifyService implements ManagedService {
    private final NextcloudService nextcloudService;
    private final SpicetifyBridgeServer bridgeServer;
    private final ItunesClient itunesClient;
    private final LrcLibClient lrcLibClient;
    private final MediaProcessRunner processRunner;
    private final ExecutorService downloadPool;

    private final AtomicBoolean isSyncing = new AtomicBoolean(false);
    private final AtomicBoolean abortFlag = new AtomicBoolean(false);
    private long lastUiUpdateTime = 0;

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

        Map<String, List<SpotifyResponses.Track>> spicetifyData;
        try {
            state.getCurrentTrackName().set("Waiting for Spicetify sync...");
            broadcastState(state, onStateUpdate, true);
            spicetifyData = bridgeServer.waitForSync(60);
        } catch (Exception e) {
            log.error("Failed to sync with Spicetify", e);
            state.getGlobalStatus().set("Critical Error");
            state.getCurrentTrackName().set(e.getMessage());
            state.getActive().set(false);
            broadcastState(state, onStateUpdate, true);
            isSyncing.set(false);
            return;
        }

        try {
            int i = 0;
            state.setTotalPlaylists(spicetifyData.size());
            for (Map.Entry<String, List<SpotifyResponses.Track>> entry : spicetifyData.entrySet()) {
                if (abortFlag.get()) break;
                String folderName = entry.getKey();
                List<SpotifyResponses.Track> tracks = entry.getValue();
                log.info("Starting Sync for Folder: {}", folderName);

                state.setCurrentPlaylistNum(++i);
                state.getCurrentTrackName().set("Processing playlist...");
                broadcastState(state, onStateUpdate, true);

                try {
                    processPlaylist(folderName, tracks, state, onStateUpdate);
                } catch (Exception e) {
                    log.error("Failed to process playlist: {}", folderName);
                    state.getCurrentTrackName().set("Failed: " + e.getMessage());
                    broadcastState(state, onStateUpdate, true);
                }
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

            boolean needScan = !abortFlag.get()
                    && "Completed".equals(state.getGlobalStatus().get())
                    && spicetifyData != null;

            if (needScan) executeNextcloudScan(spicetifyData.keySet());
        }
    }

    private void processPlaylist(String folderName,
                                 List<SpotifyResponses.Track> tracks,
                                 SpotiSyncState state,
                                 Consumer<SpotiSyncState> onUiUpdate) throws Exception {
        List<SpotifyResponses.Track> uniqueTracks = tracks.stream()
                .distinct()
                .toList();

        log.info("[{}] Extracted {} unique tracks.", folderName, uniqueTracks.size());

        if (uniqueTracks.isEmpty()) {
            log.warn("[{}] Parser found 0 tracks. Skipping.", folderName);
            state.getCurrentTrackName().set("0 tracks found. Skipping...");
            broadcastState(state, onUiUpdate, true);
            return;
        }

        Path targetDir = Paths.get(Config.MUSIC_STORAGE_ROOT, folderName);
        Files.createDirectories(targetDir);
        state.setCurrentPlaylistName(folderName);
        state.getTracksProcessedInCurrent().set(0);

        Set<String> existingFiles = getExistingFiles(targetDir);
        List<SpotifyResponses.Track> missingTracks = uniqueTracks.stream()
                .filter(track -> !existingFiles.contains(generateSafeFileName(track) + ".m4a"))
                .toList();

        log.info("[{}] Folder holds {} files. {} missing tracks queued.",
                folderName, existingFiles.size(), missingTracks.size());

        int skippedCount = uniqueTracks.size() - missingTracks.size();
        state.setTracksInCurrentPlaylist(uniqueTracks.size());
        state.getTracksProcessedInCurrent().set(skippedCount);
        state.addSkipped(skippedCount);
        broadcastState(state, onUiUpdate, true);

        List<CompletableFuture<Void>> tasks = missingTracks.stream()
                .map(track -> CompletableFuture.runAsync(() ->
                        downloadTrack(track, targetDir, state, onUiUpdate), downloadPool)
                ).toList();

        try {
            CompletableFuture.allOf(tasks.toArray(new CompletableFuture[0])).join();
            generateM3uPlaylist(targetDir, folderName);
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
                track.artists().isEmpty() ?
                        "Unknown"
                        : track.artists().getFirst().name()
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
                    log.warn("[ffmpeg] failed for '{} - {}'. Output:\n{}",
                            artist, title, errorDetails.trim());
                }

                if (attempt == maxRetries) {
                    log.error("Track permanently failed after {} attempts: '{} - {}'",
                            maxRetries, artist, title);
                    state.markTrackFailed(title);
                    return;
                }

            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                log.warn("Retry interrupted for '{} - {}'",
                        artist, title);
                return;
            } catch (Exception e) {
                log.warn("Exception for '{} - {}'. Error: {}",
                        artist, title, e.getMessage());
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

    private void generateM3uPlaylist(Path targetDir, String playlistName) {
        try {
            Path m3uPath = targetDir.resolve(playlistName + ".m3u");
            List<String> lines = new ArrayList<>();
            lines.add("#EXTM3U");

            getExistingFiles(targetDir).stream()
                    .filter(f -> f.endsWith(".m4a"))
                    .sorted()
                    .forEach(lines::add);

            Files.write(m3uPath, lines);
            log.info("Generated M3U playlist file: {}", m3uPath.getFileName());
        } catch (Exception e) {
            log.error("Failed to generate .m3u playlist", e);
        }
    }

    private void executeNextcloudScan(Set<String> folders) {
        log.info("Spotify sync completed. Triggering automatic Nextcloud index scans...");
        try {
            var musicRootPath = Paths.get(Config.MUSIC_STORAGE_ROOT);

            var fileScanResult = nextcloudService.runOccScan(musicRootPath);
            log.info("Nextcloud file auto-index finished with exit code {}:\n{}",
                    fileScanResult.exitCode(), fileScanResult.output());

            var musicScanResult = nextcloudService.runOccMusicScan();
            log.info("Nextcloud Music DB auto-index finished with exit code {}:\n{}",
                    musicScanResult.exitCode(), musicScanResult.output());

            folders.forEach(this::importNextcloudPlaylist);

        } catch (Exception e) {
            log.error("Failed to execute automatic Nextcloud index scans", e);
        }
    }

    private void importNextcloudPlaylist(String folder) {
        String relativeM3uPath = String.format("Music/%s/%s.m3u", folder, folder);

        var importResult = nextcloudService.runOccPlaylistImport(
                "Avishai",
                relativeM3uPath
        );

        if (importResult.exitCode() == 0) {
            log.info("Successfully imported playlist: {}", folder);
        } else log.error("Failed to import playlist {}:\n{}",
                folder, importResult.output()
        );
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
                : track.artists().getFirst().name();
        String rawName = artist + " - " + track.name();
        return rawName.replaceAll("[\\\\/:*?\"<>|]", "_");
    }

    private void broadcastState(
            SpotiSyncState state,
            Consumer<SpotiSyncState> onUiUpdate,
            boolean force
    ) {
        long now = System.currentTimeMillis();
        if (force || now - lastUiUpdateTime > Config.TELEGRAM_UPDATE_INTERVAL_MS) {
            onUiUpdate.accept(state);
            lastUiUpdateTime = now;
        }
    }

    @Override
    public void shutdown() {
        abortSync();
        if (downloadPool != null && !downloadPool.isShutdown()) {
            downloadPool.shutdownNow();
        }
        log.info("Spotify Service stopped.");
    }
}
