package com.avishai.bot.services.spotify;

import com.avishai.bot.network.NetworkManager;
import com.fasterxml.jackson.databind.JsonNode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import java.io.InputStream;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

@Slf4j
@RequiredArgsConstructor
public class LrcLibClient {
    private final NetworkManager network;

    public String fetchLyrics(
            String artist,
            String title,
            Path targetDir,
            String baseFileName
    ) {
        return findLocalLyrics(targetDir, baseFileName)
                .orElseGet(() -> fetchApiLyrics(artist, title));
    }

    private Optional<String> findLocalLyrics(Path targetDir, String baseFileName) {
        Path lrcPath = targetDir.resolve(baseFileName + ".lrc");
        Path txtPath = targetDir.resolve(baseFileName + ".txt");

        try {
            if (Files.exists(lrcPath)) return Optional.of(Files.readString(lrcPath));
            if (Files.exists(txtPath)) return Optional.of(Files.readString(txtPath));
        } catch (Exception e) {
            log.warn("Failed to read local lyrics file for '{}': {}",
                    baseFileName, e.getMessage());
        }
        return Optional.empty();
    }

    private String fetchApiLyrics(String artist, String title) {
        try {
            String query = artist + " " + title;
            String encodedQuery = URLEncoder.encode(query, StandardCharsets.UTF_8);
            String url = "https://lrclib.net/api/search?q=" + encodedQuery;

            HttpRequest req = HttpRequest.newBuilder(URI.create(url))
                    .GET()
                    .header("User-Agent", "HomeServerManagerBot/1.0")
                    .build();

            HttpResponse<InputStream> res = network.getHttpClient().send(
                    req,
                    HttpResponse.BodyHandlers.ofInputStream()
            );

            if (res.statusCode() == 200) {
                try (InputStream stream = res.body()) {
                    JsonNode root = network.getObjectMapper().readTree(stream);
                    if (root.isArray() && !root.isEmpty()) {
                        JsonNode firstResult = root.get(0);

                        String synced = firstResult.path("syncedLyrics")
                                .asText("");
                        String plain = firstResult.path("plainLyrics")
                                .asText("");

                        return !synced.isBlank() ? synced : plain;
                    }
                }
            }
        } catch (Exception e) {
            log.warn("LRCLIB API failed for '{} - {}': {}",
                    artist, title, e.getMessage());
        }
        return "";
    }
}
