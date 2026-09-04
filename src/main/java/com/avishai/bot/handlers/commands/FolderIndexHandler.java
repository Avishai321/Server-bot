package com.avishai.bot.handlers.commands;

import com.avishai.bot.core.CommandContext;
import com.avishai.bot.core.Config;
import com.avishai.bot.core.ShellExecutionService;
import com.avishai.bot.utils.BotCommands;
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
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Stream;

@Slf4j
@RequiredArgsConstructor
public class FolderIndexHandler implements CommandHandler {
    private final ExecutorService executorService;
    private final Map<String, Path> pathCache = new ConcurrentHashMap<>();

    @Override
    public List<String> getCommandSignature() {
        return List.of(BotCommands.INDEX_FOLDER, "/idx_nav", "/idx_run");
    }

    @Override
    public void handle(CommandContext ctx) {
        String action = ctx.getActionData();
        Integer msgId = ctx.getMessageId();
        Path rootPath = Paths.get(Config.INDEX_ROOT_PATH);

        if (action.equals(BotCommands.INDEX_FOLDER)) {
            sendDirectoryMenu(ctx, rootPath, msgId);
        } else if (action.startsWith("/idx_nav ")) {
            Path targetPath = pathCache.getOrDefault(action.substring(9).trim(), rootPath);
            sendDirectoryMenu(ctx, targetPath, msgId);
        } else if (action.startsWith("/idx_run ")) {
            Path targetPath = pathCache.getOrDefault(action.substring(9).trim(), rootPath);
            executorService.submit(() -> startIndexingProcess(ctx, targetPath, msgId));
        }
    }

    private void sendDirectoryMenu(CommandContext ctx, Path currentDir, Integer messageId) {
        Path validDir = (Files.exists(currentDir) && Files.isDirectory(currentDir))
                ? currentDir
                : Paths.get(Config.INDEX_ROOT_PATH);

        List<List<InlineKeyboardButton>> rows = new ArrayList<>();

        rows.add(List.of(createButton(
                "✅ Index: " + validDir.getFileName(),
                "/idx_run " + registerPath(validDir)
        )));

        if (validDir.getParent() != null) {
            rows.add(List.of(createButton(
                    "🔙 Go Up",
                    "/idx_nav " + registerPath(validDir.getParent())
            )));
        }

        try (Stream<Path> paths = Files.list(validDir)) {
            List<Path> subDirs = paths.filter(Files::isDirectory)
                    .sorted().limit(30)
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

        String text = "📂 <b>Directory Explorer</b>\n<b>Location:</b> <code>" +
                validDir.toAbsolutePath() + "</code>";

        if (messageId != null) ctx.edit(messageId, text, markup);
        else ctx.reply(text, markup);
    }

    private void startIndexingProcess(CommandContext ctx, Path targetPath, Integer messageId) {
        ctx.edit(messageId,
                "⚙️ <b>Indexing Starting...</b>\n<b>Target:</b> <code>" +
                        targetPath.toAbsolutePath() + "</code>"
        );

        AtomicInteger filesProcessed = new AtomicInteger(0);
        AtomicLong lastUpdateTime = new AtomicLong(System.currentTimeMillis());

        ShellExecutionService.executeStream(
                List.of("bash", "-c", "find '" + targetPath.toAbsolutePath() + "' -type f"),

                // onOutput handler
                line -> {
                    int count = filesProcessed.incrementAndGet();
                    long now = System.currentTimeMillis();

                    if (now - lastUpdateTime.get() > Config.TELEGRAM_UPDATE_INTERVAL_MS) {
                        ctx.edit(messageId,
                                String.format("""
                                                ⚙️ <b>Indexing In Progress...</b>
                                                <b>Target:</b> <code>%s</code>
                                                <b>Scanned:</b> %d files
                                                <b>Log:</b> <pre>%s</pre>""",
                                        targetPath.toAbsolutePath(),
                                        count,
                                        escapeHtml(truncate(line)))
                        );
                        lastUpdateTime.set(now);
                    }
                },

                // onComplete handler
                exitCode -> ctx.edit(messageId,
                        String.format("""
                                        ✅ <b>Indexing Task Completed</b>
                                        <b>Target:</b> <code>%s</code>
                                        <b>Files Scanned:</b> %d
                                        <b>Exit Code:</b> %d""",
                                targetPath.toAbsolutePath(),
                                filesProcessed.get(),
                                exitCode)
                )
        );
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

    private String escapeHtml(String text) {
        return text == null ? "" : text.replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;");
    }

    private String truncate(String text) {
        return text.length() <= 50 ? text : "..." + text.substring(text.length() - 50 + 3);
    }
}
