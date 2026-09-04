package com.avishai.bot.handlers.commands;

import com.avishai.bot.core.CommandContext;
import com.avishai.bot.utils.BotCommands;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.InlineKeyboardMarkup;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.buttons.InlineKeyboardButton;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Stream;

@Slf4j
@RequiredArgsConstructor
public class FolderIndexHandler implements CommandHandler {
    private static final String ROOT_PATH_STR = "/mnt/d/data";
    private static final Path ROOT_PATH = Paths.get(ROOT_PATH_STR);
    private static final Path BOUNDARY_PATH = Paths.get("/mnt/d");

    private final ExecutorService executorService;
    private final Map<String, Path> pathCache = new ConcurrentHashMap<>();

    private final AtomicBoolean isIndexing = new AtomicBoolean(false);
    private volatile Process currentProcess = null;

    private static String getOccCommand(Path targetPath) {
        String targetStr = targetPath.toAbsolutePath().toString();

        if (targetStr.equals(ROOT_PATH_STR) || !targetStr.startsWith(ROOT_PATH_STR)) {
            return "php occ files:scan --all";
        }

        String relativePath = targetStr.substring(ROOT_PATH_STR.length());
        if (relativePath.startsWith("/")) {
            relativePath = relativePath.substring(1);
        }

        return "php occ files:scan --path=\"" + relativePath + "\"";
    }

    @Override
    public List<String> getCommandSignature() {
        return List.of(BotCommands.INDEX_FOLDER, "/idx_nav", "/idx_run", "/idx_stop");
    }

    @Override
    public void handle(CommandContext ctx) {
        String action = extractCommand(ctx);
        Integer msgId = extractMessageId(ctx);

        if (action.equals(BotCommands.INDEX_FOLDER)) {
            sendDirectoryMenu(ctx, ROOT_PATH, msgId);
        } else if (action.startsWith("/idx_nav ")) {
            String id = action.replace("/idx_nav ", "").trim();
            sendDirectoryMenu(ctx, pathCache.getOrDefault(id, ROOT_PATH), msgId);
        } else if (action.startsWith("/idx_run ")) {
            String id = action.replace("/idx_run ", "").trim();
            Path targetPath = pathCache.getOrDefault(id, ROOT_PATH);
            executorService.submit(() -> startIndexingProcess(ctx, targetPath, msgId));
        } else if (action.equals("/idx_stop")) {
            executorService.submit(() -> abortIndexingProcess(ctx, msgId));
        }
    }

    private void sendDirectoryMenu(CommandContext ctx, Path currentDir, Integer messageId) {
        Path validDir = (Files.exists(currentDir) && Files.isDirectory(currentDir))
                ? currentDir
                : ROOT_PATH;

        List<List<InlineKeyboardButton>> rows = new ArrayList<>();

        rows.add(List.of(createButton(
                "✅ Index: " + validDir.getFileName(),
                "/idx_run " + registerPath(validDir)
        )));

        Path parent = validDir.getParent();
        if (parent != null && parent.startsWith(BOUNDARY_PATH)) {
            rows.add(List.of(createButton(
                    "🔙 Go Up to " + parent.getFileName(),
                    "/idx_nav " + registerPath(parent)
            )));
        }

        try (Stream<Path> paths = Files.list(validDir)) {
            List<Path> subDirs = paths
                    .filter(Files::isDirectory)
                    .sorted()
                    .limit(30)
                    .toList();

            List<InlineKeyboardButton> currentRow = new ArrayList<>();

            for (Path subDir : subDirs) {
                currentRow.add(createButton(
                        "📁 " + subDir.getFileName(),
                        "/idx_nav " + registerPath(subDir)
                ));

                if (currentRow.size() == 2) {
                    rows.add(currentRow);
                    currentRow = new ArrayList<>();
                }
            }
            if (!currentRow.isEmpty()) rows.add(currentRow);

        } catch (Exception e) {
            log.error("Failed to read directory: {}", validDir, e);
        }

        InlineKeyboardMarkup markup = new InlineKeyboardMarkup();
        markup.setKeyboard(rows);

        String uiTemplate = """
                📂 <b>Server Index Explorer</b>
                <b>Location:</b> <code>%s</code>
                
                Navigate or execute sync:""";

        String text = String.format(uiTemplate, validDir.toAbsolutePath());

        if (messageId != null) ctx.edit(messageId, text, markup);
        else ctx.reply(text, markup);
    }

    private void startIndexingProcess(CommandContext ctx, Path targetPath, Integer messageId) {
        if (!isIndexing.compareAndSet(false, true)) {
            ctx.edit(
                    messageId,
                    "⚠️ <b>Action Denied:</b> Another bot indexing task is currently running."
            );
            return;
        }

        long startTime = System.currentTimeMillis();
        String initUi = """
                ⚙️ <b>Nextcloud Indexing Started...</b>
                <b>Target:</b> <code>%s</code>""";
        ctx.edit(messageId, String.format(initUi, targetPath.toAbsolutePath()), getStopKeyboard());

        try (ScheduledExecutorService uiScheduler = Executors.newSingleThreadScheduledExecutor()) {
            try {
                String occCommand = getOccCommand(targetPath);
                List<String> command = List.of(
                        "docker", "exec", "--user", "www-data",
                        "nextcloud-server-app-1", "bash", "-c", occCommand
                );

                ProcessBuilder pb = new ProcessBuilder(command);
                pb.redirectErrorStream(true);
                currentProcess = pb.start();

                uiScheduler.scheduleAtFixedRate(() -> {
                    long elapsed = (System.currentTimeMillis() - startTime) / 1000;
                    String liveUi = """
                            ⚙️ <b>Syncing Nextcloud Database...</b>
                            <b>Target:</b> <code>%s</code>
                            
                            ⏱ <b>Elapsed Time:</b> %ds
                            <i>Scanning files in background...</i>""";

                    ctx.edit(
                            messageId,
                            String.format(liveUi, targetPath.toAbsolutePath(), elapsed),
                            getStopKeyboard()
                    );
                }, 1, 1, TimeUnit.SECONDS);

                // Block and wait for the process to finish, reading all output at once
                String rawOutput = new String(
                        currentProcess.getInputStream().readAllBytes(),
                        StandardCharsets.UTF_8
                );

                int exitCode = currentProcess.waitFor();
                String finalOutput = rawOutput.replaceAll("\u001B\\[[;\\d]*m", "").trim();

                renderFinalState(ctx, messageId, targetPath, exitCode, finalOutput);

            } catch (Exception e) {
                log.error("Indexing process failed", e);
                ctx.edit(messageId, "❌ <b>Process Crashed</b>\n<pre>" + escapeHtml(e.getMessage()) + "</pre>");
            } finally {
                uiScheduler.shutdownNow(); // Gracefully stops the 1-second timer
                currentProcess = null;
                isIndexing.set(false);
            }
        }
    }

    private void renderFinalState(
            CommandContext ctx,
            Integer messageId,
            Path targetPath,
            int exitCode,
            String finalOutput) {

        if (finalOutput.contains("Another process is already scanning")) {
            String busyUi = """
                    ⚠️ <b>Server Busy</b>
                    <b>Target:</b> <code>%s</code>
                    
                    Nextcloud is currently indexing this folder in the background (likely from a previous run).
                    Please wait a few minutes before trying again.""";
            ctx.edit(messageId, String.format(busyUi, targetPath.toAbsolutePath()), null);
            return;
        }

        if (exitCode == 137 || exitCode == 143) {
            String abortedUi = """
                    🛑 <b>Indexing Aborted by User</b>
                    <b>Target:</b> <code>%s</code>""";
            ctx.edit(messageId, String.format(abortedUi, targetPath.toAbsolutePath()), null);
            return;
        }

        String title = (exitCode == 0)
                ? "✅ <b>Indexing Completed</b>"
                : "❌ <b>Indexing Failed (Code: " + exitCode + ")</b>";

        String completeUi = """
                %s
                <b>Target:</b> <code>%s</code>
                
                <b>Final Output:</b>
                <pre>%s</pre>""";

        ctx.edit(messageId,
                String.format(completeUi, title, targetPath.toAbsolutePath(), escapeHtml(finalOutput)),
                null);
    }

    private void abortIndexingProcess(CommandContext ctx, Integer messageId) {
        if (isIndexing.get() && currentProcess != null) {
            ctx.edit(messageId, "⚠️ <i>Executing kill command in Nextcloud container...</i>");

            try {
                ProcessBuilder pb = new ProcessBuilder(
                        "docker", "exec", "--user", "www-data",
                        "nextcloud-server-app-1", "pkill", "-f", "occ files:scan"
                );
                pb.start().waitFor();

                currentProcess.destroyForcibly();
            } catch (Exception e) {
                log.error("Failed to kill container process", e);
            }
        } else {
            ctx.edit(messageId, "ℹ️ No indexing process is currently running.");
        }
    }

    private InlineKeyboardMarkup getStopKeyboard() {
        InlineKeyboardMarkup markup = new InlineKeyboardMarkup();
        markup.setKeyboard(List.of(List.of(
                createButton("🛑 Stop Indexing", "/idx_stop")
        )));
        return markup;
    }

    private String registerPath(Path path) {
        String id = UUID.randomUUID().toString().substring(0, 8);
        if (pathCache.size() > 500) pathCache.clear();

        pathCache.put(id, path);
        return id;
    }

    private InlineKeyboardButton createButton(String text, String callbackData) {
        InlineKeyboardButton btn = new InlineKeyboardButton();
        btn.setText(text);
        btn.setCallbackData(callbackData);
        return btn;
    }

    private String extractCommand(CommandContext ctx) {
        if (ctx.update().hasCallbackQuery()) {
            return ctx.update().getCallbackQuery().getData();
        }
        if (ctx.update().hasMessage() && ctx.update().getMessage().hasText()) {
            return ctx.update().getMessage().getText();
        }
        return "";
    }

    private Integer extractMessageId(CommandContext ctx) {
        if (ctx.update().hasCallbackQuery()) {
            return ctx.update().getCallbackQuery().getMessage().getMessageId();
        }
        return null;
    }

    private String escapeHtml(String text) {
        return text == null
                ? ""
                : text.replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;");
    }
}
