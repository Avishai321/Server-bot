package com.avishai.bot.handlers;

import com.avishai.bot.config.BotCommands;
import com.avishai.bot.core.CommandContext;
import com.avishai.bot.core.TelegramUi;
import com.avishai.bot.services.NextcloudService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.InlineKeyboardMarkup;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.buttons.InlineKeyboardButton;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

@Slf4j
@RequiredArgsConstructor
public class FolderIndexHandler implements CommandHandler {
    private static final Path ROOT_PATH = Paths.get(NextcloudService.ROOT_PATH_STR);
    private static final Path BOUNDARY_PATH = Paths.get("/mnt/d");
    private final ExecutorService executorService;
    private final NextcloudService nextcloudService;
    private final Map<String, Path> pathCache = new ConcurrentHashMap<>();

    @Override
    public List<String> getCommandSignature() {
        return List.of(BotCommands.INDEX_FOLDER, "/idx_nav", "/idx_run", "/idx_stop");
    }

    @Override
    public HandlerCategory getCategory() {
        return HandlerCategory.STORAGE_AND_MEDIA;
    }

    @Override
    public String getDescription() {
        return "Index specific server directories";
    }

    @Override
    public void handle(CommandContext ctx) {
        String action = ctx.getActionData();
        Integer msgId = ctx.getMessageId();

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
        InlineKeyboardButton indexButton = TelegramUi.button(
                "✅ Index: " + validDir.getFileName(),
                "/idx_run " + registerPath(validDir)
        );

        Path parent = validDir.getParent();
        if (parent != null && parent.startsWith(BOUNDARY_PATH)) {
            InlineKeyboardButton upButton = TelegramUi.button(
                    "🔙 Go Up to " + parent.getFileName(),
                    "/idx_nav " + registerPath(parent)
            );
            rows.add(List.of(indexButton, upButton));
        } else rows.add(List.of(indexButton));


        try (Stream<Path> paths = Files.list(validDir)) {
            List<Path> subDirs = paths
                    .filter(Files::isDirectory)
                    .sorted()
                    .limit(30)
                    .toList();

            rows.addAll(TelegramUi.createGrid(
                    subDirs,
                    2,
                    dir -> TelegramUi.button(
                            "📁 " + dir.getFileName(),
                            "/idx_nav " + registerPath(dir)
                    ))
            );
        } catch (Exception e) {
            log.error("Failed to read directory: {}", validDir, e);
        }

        InlineKeyboardMarkup markup = new InlineKeyboardMarkup();
        markup.setKeyboard(rows);

        String text = String.format("""
                📂 <b>Server Index Explorer</b>
                <b>Location:</b> <code>%s</code>
                
                Navigate or execute sync:""", validDir.toAbsolutePath());

        if (messageId != null) ctx.edit(messageId, text, markup);
        else ctx.reply(text, markup);
    }

    private void startIndexingProcess(CommandContext ctx, Path targetPath, Integer messageId) {
        if (nextcloudService.isBusy()) {
            ctx.edit(messageId, "⚠️ <b>Action Denied:</b> Another indexing task is currently running.");
            return;
        }

        long startTime = System.currentTimeMillis();
        ctx.edit(messageId, String.format("""
                        ⚙️ <b>Nextcloud Indexing Started...</b>
                        <b>Target:</b> <code>%s</code>""", targetPath.toAbsolutePath()),
                TelegramUi.singleButtonKeyboard("🛑 Stop Indexing", "/idx_stop"));

        NextcloudService.NextcloudSyncResult result;
        try (ScheduledExecutorService uiScheduler = Executors.newSingleThreadScheduledExecutor()) {
            uiScheduler.scheduleAtFixedRate(() -> {
                long elapsed = (System.currentTimeMillis() - startTime) / 1000;
                ctx.edit(messageId, String.format("""
                                ⚙️ <b>Syncing Nextcloud Database...</b>
                                <b>Target:</b> <code>%s</code>
                                
                                ⏱ <b>Elapsed Time:</b> %ds
                                <i>Scanning files in background...</i>""", targetPath.toAbsolutePath(), elapsed),
                        TelegramUi.singleButtonKeyboard("🛑 Stop Indexing", "/idx_stop"));
            }, 1, 1, TimeUnit.SECONDS);

            result = nextcloudService.runOccScan(targetPath);
            uiScheduler.shutdownNow();
        }
        renderFinalState(ctx, messageId, targetPath, result);
    }

    private void renderFinalState(
            CommandContext ctx,
            Integer messageId,
            Path targetPath,
            NextcloudService.NextcloudSyncResult result
    ) {
        if (result.output().contains("Another process is already scanning")) {
            ctx.edit(messageId, String.format("""
                      <b>Server Busy</b>
                    <b>Target:</b> <code>%s</code>
                    
                    Nextcloud is currently indexing this folder in the background (likely from a previous run).
                    Please wait a few minutes before trying again.""", targetPath.toAbsolutePath()));
            return;
        }

        if (result.exitCode() == 137 || result.exitCode() == 143) {
            ctx.edit(messageId, String.format("""
                      <b>Indexing Aborted by User</b>
                    <b>Target:</b> <code>%s</code>""", targetPath.toAbsolutePath()));
            return;
        }

        String title = (result.exitCode() == 0)
                ? "  <b>Indexing Completed</b>"
                : "  <b>Indexing Failed (Code: " + result.exitCode() + ")</b>";

        String outputUI;
        // Regex to extract the 7 data columns from the ASCII table
        Pattern pattern = Pattern.compile(
                "\\|\\s*(\\d+)\\s*\\|\\s*(\\d+)\\s*\\|\\s*(\\d+)\\s*\\" +
                        "|\\s*(\\d+)\\s*\\|\\s*(\\d+)\\s*\\|\\s*(\\d+)\\s*\\" +
                        "|\\s*([\\d:]+)\\s*\\|");
        Matcher m = pattern.matcher(result.output());

        if (m.find()) outputUI = String.format("""
                        📁 <b>Folders:</b> %s | 📄 <b>Files:</b> %s
                        ✨ <b>New:</b> %s | 🔄 <b>Updated:</b> %s | 🗑️ <b>Removed:</b> %s
                        ⏱️ <b>Time:</b> %s | ⚠️ <b>Errors:</b> %s""",
                m.group(1), m.group(2),
                m.group(3), m.group(4), m.group(5),
                m.group(7), m.group(6));
            // Fallback if the output doesn't match the expected table format
        else outputUI = "<pre>" + TelegramUi.escapeHtml(result.output()) + "</pre>";

        ctx.edit(messageId, String.format("""
                        %s
                        <b>Target:</b> <code>%s</code>
                        
                        <b>Results:</b>
                        %s""",
                title,
                targetPath.toAbsolutePath(),
                outputUI)
        );
    }

    private void abortIndexingProcess(CommandContext ctx, Integer messageId) {
        if (nextcloudService.isBusy()) {
            ctx.edit(messageId, "⚠️ <i>Executing kill command in Nextcloud container...</i>");
            nextcloudService.abortScan();
        } else ctx.edit(messageId, "ℹ️ No indexing process is currently running.");
    }

    private String registerPath(Path path) {
        String id = UUID.randomUUID().toString().substring(0, 8);
        if (pathCache.size() > 500) pathCache.clear();
        pathCache.put(id, path);
        return id;
    }
}
