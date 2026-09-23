package com.avishai.bot.services.spotify;

import com.avishai.bot.models.spotify.SpotifyResponses;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import com.avishai.bot.core.ManagedService;

@Slf4j
public class SpicetifyBridgeServer implements ManagedService {
    private static final int PORT = 8081;
    
    private final HttpServer server;
    private final ObjectMapper mapper = new ObjectMapper();
    private final AtomicBoolean needsSync = new AtomicBoolean(false);
    private final Map<String, List<SpotifyResponses.Track>> cachedPlaylists;
    
    private CountDownLatch syncLatch;

    public SpicetifyBridgeServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress("0.0.0.0", PORT), 0);

        server.createContext("/sync-check", exchange -> {
            exchange.getResponseHeaders().add("Access-Control-Allow-Origin", "*");
            exchange.getResponseHeaders().add("Access-Control-Allow-Private-Network", "true");
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            
            try {
                String response = needsSync.get()
                        ? "{\"needsSync\": true}"
                        : "{\"needsSync\": false}";
                
                byte[] bytes = response.getBytes();
                exchange.sendResponseHeaders(200, bytes.length);
                try (var os = exchange.getResponseBody()) {
                    os.write(bytes);
                }
            } catch (Exception e) {
                log.error("Failed to write sync-check response", e);
                exchange.sendResponseHeaders(500, 0);
                exchange.getResponseBody().close();
            }
        });

        server.createContext("/sync-data", exchange -> {
            exchange.getResponseHeaders().add("Access-Control-Allow-Origin", "*");
            exchange.getResponseHeaders().add("Access-Control-Allow-Private-Network", "true");
            if ("OPTIONS".equalsIgnoreCase(exchange.getRequestMethod())) {
                exchange.getResponseHeaders().add("Access-Control-Allow-Methods", "POST, OPTIONS");
                exchange.getResponseHeaders().add("Access-Control-Allow-Headers", "Content-Type");
                exchange.sendResponseHeaders(204, -1);
                return;
            }

            if ("POST".equalsIgnoreCase(exchange.getRequestMethod())) {
                try {
                    JsonNode root = mapper.readTree(exchange.getRequestBody());
                    parseAndCachePayload(root);
                    needsSync.set(false);
                    if (syncLatch != null) syncLatch.countDown();
                    String response = "{\"status\": \"ok\"}";
                    byte[] bytes = response.getBytes();
                    exchange.sendResponseHeaders(200, bytes.length);
                    try (var os = exchange.getResponseBody()) {
                        os.write(bytes);
                    }
                } catch (Exception e) {
                    log.error("Failed to parse sync data", e);
                    exchange.sendResponseHeaders(500, 0);
                    exchange.getResponseBody().close();
                }
            } else {
                exchange.sendResponseHeaders(405, -1);
                exchange.getResponseBody().close();
            }
        });

        this.cachedPlaylists = new ConcurrentHashMap<>();
    }

    public void start() {
        server.start();
        log.info("Spicetify Bridge Server started on port {}", server.getAddress().getPort());
    }

    @Override
    public void shutdown() {
        server.stop(0);
        log.info("Spicetify Bridge Server stopped.");
    }

    private void parseAndCachePayload(JsonNode root) {
        cachedPlaylists.clear();
        if (root.isArray()) {
            for (JsonNode playlistNode : root) {
                String folderName = playlistNode.path("folderName").asText();
                if (folderName == null || folderName.isBlank()) continue;

                List<SpotifyResponses.Track> tracks = new ArrayList<>();
                for (JsonNode trackNode : playlistNode.path("tracks")) {
                    String name = trackNode.path("name").asText();
                    String artistName = trackNode.path("artist").asText();
                    var artist = new SpotifyResponses.Artist(artistName);
                    tracks.add(new SpotifyResponses.Track(name, List.of(artist), null, ""));
                }
                cachedPlaylists.put(folderName, tracks);
            }
        }
        log.info("Cached {} playlists from Spicetify", cachedPlaylists.size());
    }

    public Map<String, List<SpotifyResponses.Track>> waitForSync(
            int timeoutSeconds
    ) throws InterruptedException {
        syncLatch = new CountDownLatch(1);
        needsSync.set(true);
        log.info("Waiting for Spicetify to provide data...");
        
        boolean arrived = syncLatch.await(timeoutSeconds, TimeUnit.SECONDS);
        needsSync.set(false);
        
        if (arrived) return cachedPlaylists;

        throw new RuntimeException("Timeout waiting for Spicetify to send data. " +
                "Ensure Spotify Desktop is running.");
    }
}
