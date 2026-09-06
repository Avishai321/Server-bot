package com.avishai.bot.services.spotify;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

@Slf4j
@RequiredArgsConstructor
public class ItunesClient {
    private final HttpClient httpClient;
    private final ObjectMapper mapper;

    public ItunesMetadata fetchItunesMetadata(String artist, String title) {
        return fetchRawJson(artist, title)
                .map(this::parseMetadataNode)
                .orElseGet(ItunesMetadata::empty);
    }

    private Optional<JsonNode> fetchRawJson(String artist, String title) {
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
                JsonNode results = root.path("results");
                if (results.isArray() && !results.isEmpty()) {
                    return Optional.of(results.get(0));
                }
            }
        } catch (Exception e) {
            log.warn("iTunes API failed for '{} - {}': {}", artist, title, e.getMessage());
        }
        return Optional.empty();
    }

    private ItunesMetadata parseMetadataNode(JsonNode node) {
        String artworkUrl = node.path("artworkUrl100")
                .asText("").replace("100x100bb.jpg", "600x600bb.jpg");
        String genre = node.path("primaryGenreName").asText("");

        String releaseDate = node.path("releaseDate").asText("");
        String releaseYear = releaseDate.length() >= 4 ? releaseDate.substring(0, 4) : "";

        Integer trackNum = node.has("trackNumber") ? node.get("trackNumber").asInt() : null;
        Integer trackCount = node.has("trackCount") ? node.get("trackCount").asInt() : null;
        Integer discNum = node.has("discNumber") ? node.get("discNumber").asInt() : null;
        Integer discCount = node.has("discCount") ? node.get("discCount").asInt() : null;

        return new ItunesMetadata(artworkUrl, genre, releaseYear, trackNum, trackCount, discNum, discCount);
    }

    public boolean downloadImage(String urlStr, Path targetPath) {
        if (urlStr == null || urlStr.isEmpty()) return false;
        try {
            HttpRequest req = HttpRequest.newBuilder(URI.create(urlStr))
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

    public record ItunesMetadata(
            String coverUrl,
            String genre,
            String releaseYear,
            Integer trackNumber,
            Integer trackCount,
            Integer discNumber,
            Integer discCount
    ) {
        public static ItunesMetadata empty() {
            return new ItunesMetadata(
                    "", "", "",
                    null, null, null, null
            );
        }
    }
}
