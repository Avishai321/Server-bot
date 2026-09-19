package com.avishai.bot.services;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;
import lombok.extern.slf4j.Slf4j;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

@Slf4j
public class LiveLogServer {
    private HttpServer server;
    private ExecutorService httpThreadPool;

    public void start(int port) {
        try {
            server = HttpServer.create(new InetSocketAddress(port), 0);
            server.createContext("/logs/live", new HtmlHandler());
            server.createContext("/logs/stream", new SseStreamHandler());

            httpThreadPool = Executors.newFixedThreadPool(5);
            server.setExecutor(httpThreadPool);

            server.start();
            log.info("Live HTTP log server initialized on port {}", port);
        } catch (Exception e) {
            log.error("Failed to start HTTP server", e);
        }
    }

    public void stop() {
        if (server != null) {
            server.stop(0);
            log.info("Live HTTP log server stopped.");
        }
        if (httpThreadPool != null && !httpThreadPool.isShutdown()) {
            httpThreadPool.shutdownNow();
        }
    }

    private int extractLines(URI uri) {
        String query = uri.getQuery();
        if (query != null) {
            for (String param : query.split("&")) {
                String[] pair = param.split("=");
                if (pair.length == 2 && pair[0].equals("lines")) {
                    try {
                        return Integer.parseInt(pair[1]);
                    } catch (NumberFormatException ignored) {
                    }
                }
            }
        }
        return 50;
    }

    private class HtmlHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            int lines = extractLines(exchange.getRequestURI());
            String html = String.format("""
                     <!DOCTYPE html>
                     <html lang="en">
                     <head>
                         <title>Daemon Telemetry</title>
                         <style>
                             body { background: #121212; color: #00ff00;\s
                                    font-family: monospace; padding: 20px; font-size: 14px; }
                             #logs { white-space: pre-wrap; word-wrap: break-word; }
                         </style>
                     </head>
                     <body>
                         <div id="logs">Loading live stream...</div>
                         <script>
                             const evtSource = new EventSource('/logs/stream?lines=%d');
                             const logs = document.getElementById('logs');
                             logs.innerHTML = '';
                            \s
                             evtSource.onmessage = function(e) {
                                 const div = document.createElement('div');
                                 div.textContent = e.data;
                                 logs.appendChild(div);
                                 window.scrollTo(0, document.body.scrollHeight);
                             };
                            \s
                             evtSource.onerror = function() {
                                 const div = document.createElement('div');
                                 div.textContent = "[Connection lost. Auto-reconnecting...]";
                                 div.style.color = "orange";
                                 logs.appendChild(div);
                                 // Native EventSource will now auto-reconnect in ~3 seconds
                             };
                         </script>
                     </body>
                     </html>
                    \s""", lines);

            byte[] bytes = html.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "text/html; charset=UTF-8");
            exchange.sendResponseHeaders(200, bytes.length);

            try (OutputStream os = exchange.getResponseBody()) {
                os.write(bytes);
            }
        }
    }

    private class SseStreamHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            exchange.getResponseHeaders().add("Content-Type", "text/event-stream; charset=UTF-8");
            exchange.getResponseHeaders().add("Cache-Control", "no-cache");
            exchange.getResponseHeaders().add("Connection", "keep-alive");
            exchange.sendResponseHeaders(200, 0);

            int lines = extractLines(exchange.getRequestURI());
            ProcessBuilder pb = new ProcessBuilder(
                    "journalctl", "-u", "telegram-bot", "-f", "-n", String.valueOf(lines)
            );

            Process process = pb.start();

            // Line break applied here to prevent horizontal scrolling
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8));
                 OutputStream os = exchange.getResponseBody()) {

                String line;
                while ((line = reader.readLine()) != null) {
                    String payload = "data: " + line + "\n\n";
                    os.write(payload.getBytes(StandardCharsets.UTF_8));
                    os.flush();
                }
            } catch (IOException e) {
                log.info("Client disconnected from live stream. Terminating journalctl process.");
            } finally {
                if (process.isAlive()) {
                    process.destroyForcibly();
                }
            }
        }
    }
}
