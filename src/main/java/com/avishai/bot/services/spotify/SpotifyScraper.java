package com.avishai.bot.services.spotify;

import com.avishai.bot.models.spotify.SpotifyResponses;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static java.util.Base64.getDecoder;

@RequiredArgsConstructor
public class SpotifyScraper {
    private static final Pattern PLAYLIST_ID_PATTERN =
            Pattern.compile("playlist/([a-zA-Z0-9]+)");
    private static final Pattern NEXT_DATA_PATTERN =
            Pattern.compile("<script id=\"__NEXT_DATA__\" type=\"application/json\">(.*?)</script>");
    private static final Pattern INITIAL_STATE_PATTERN =
            Pattern.compile("<script id=\"initial-state\" type=\"text/plain\">(.*?)</script>");

    private final HttpClient httpClient;
    private final ObjectMapper mapper;

    public List<SpotifyResponses.Track> extractTracks(String link, String folderName) throws Exception {
        String playlistId = extractPlaylistId(link);
        if (playlistId.isEmpty()) {
            throw new IllegalArgumentException("Invalid Spotify link for: " + folderName);
        }

        String html = fetchPlaylistHtml(playlistId);
        String jsonPayload = extractJsonPayload(html);
        if (jsonPayload == null) {
            throw new IllegalStateException("Could not locate JSON metadata inside the HTML.");
        }

        JsonNode root = mapper.readTree(jsonPayload);
        List<SpotifyResponses.Track> allTracks = new ArrayList<>();
        findTracksRecursively(root, allTracks);
        return allTracks;
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
            if (a.has("name")) artists.add(new SpotifyResponses.Artist(a.get("name").asText()));
        });

        SpotifyResponses.Album albumObj = null;
        if (node.has("album")) {
            JsonNode albumNode = node.get("album");
            String albumName = albumNode.has("name") ? albumNode.get("name").asText() : "";
            String releaseDate = albumNode.has("release_date") ? albumNode.get("release_date").asText() : "";
            albumObj = new SpotifyResponses.Album(albumName, releaseDate);
        }
        return new SpotifyResponses.Track(name, artists, albumObj, "");
    }

    private SpotifyResponses.Track parseEmbedTrack(JsonNode node) {
        String name = node.get("title").asText();
        String artist = node.get("subtitle").asText();
        return new SpotifyResponses.Track(name, List.of(new SpotifyResponses.Artist(artist)), null, "");
    }
}
