package com.avishai.bot.handlers.commands;

import com.avishai.bot.core.CommandContext;
import com.avishai.bot.core.ShellExecutionService;
import com.avishai.bot.utils.BotCommands;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.InlineKeyboardMarkup;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.buttons.InlineKeyboardButton;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;

public class DockerManagerHandler implements CommandHandler {
    private static final Logger log = LoggerFactory.getLogger(DockerManagerHandler.class);
    private final ExecutorService executorService;

    public DockerManagerHandler(ExecutorService executorService) {
        this.executorService = executorService;
    }

    @Override
    public List<String> getCommandSignature() {
        return List.of(BotCommands.DOCKER_MANAGER);
    }

    @Override
    public void handle(CommandContext ctx) {
        String fullCommand = extractCommand(ctx);

        // Variables must be effectively final for thread injection
        final Integer finalMsgId = extractMessageId(ctx);

        if (fullCommand.equals(BotCommands.DOCKER_MANAGER)) {
            executorService.submit(() -> sendMainList(ctx, finalMsgId));

        } else if (fullCommand.startsWith("/docker menu ")) {
            String name = fullCommand.replace("/docker menu ", "").trim();
            executorService.submit(() -> sendContainerMenu(ctx, name, finalMsgId));

        } else if (fullCommand.startsWith("/docker restart ")) {
            String name = fullCommand.replace("/docker restart ", "").trim();
            executorService.submit(() -> restartContainer(ctx, name, finalMsgId));

        } else if (fullCommand.startsWith("/docker logs ")) {
            String[] parts = fullCommand.split("\\s+");
            if (parts.length >= 3) {
                String name = parts[2];
                int lines = (parts.length >= 4) ? parseLinesArg(parts[3]) : 20;
                String format = (parts.length >= 5) ? parts[4].toLowerCase() : "auto";

                executorService.submit(() -> fetchLogs(ctx, name, lines, format));
            }
        }
    }

    private String extractCommand(CommandContext ctx) {
        if (ctx.update().hasCallbackQuery()) {
            return ctx.update().getCallbackQuery().getData();
        } else if (ctx.update().hasMessage() && ctx.update().getMessage().hasText()) {
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

    private int parseLinesArg(String value) {
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException e) {
            return 20;
        }
    }

    private void sendMainList(CommandContext ctx, Integer messageId) {
        var command = List.of("docker", "ps", "--format", "{{.Names}}");
        var response = ShellExecutionService.execute(command);

        if (!response.isSuccess()) {
            ctx.reply("⚠️ Failed to reach Docker engine.\n<pre>" + response.error() + "</pre>");
            return;
        }

        String text = "🐳 <b>Docker Management</b>\n━━━━━━━━━━━━━━━━━━\nSelect a container to manage:";
        String[] containers = response.output().split("\n");

        InlineKeyboardMarkup markup = new InlineKeyboardMarkup();
        markup.setKeyboard(buildContainerGrid(containers));

        if (messageId != null) ctx.edit(messageId, text, markup);
        else ctx.reply(text, markup);
    }

    private List<List<InlineKeyboardButton>> buildContainerGrid(String[] containers) {
        List<List<InlineKeyboardButton>> rows = new ArrayList<>();
        List<InlineKeyboardButton> currentRow = new ArrayList<>();

        for (String name : containers) {
            if (name.trim().isEmpty()) continue;

            InlineKeyboardButton btn = new InlineKeyboardButton();
            btn.setText("📦 " + name);
            btn.setCallbackData("/docker menu " + name);
            currentRow.add(btn);

            if (currentRow.size() == 2) {
                rows.add(currentRow);
                currentRow = new ArrayList<>();
            }
        }
        if (!currentRow.isEmpty()) {
            rows.add(currentRow);
        }
        return rows;
    }

    private void sendContainerMenu(CommandContext ctx, String name, Integer messageId) {
        var command = List.of("docker", "ps", "--filter", "name=^/" + name + "$", "--format", "{{.Status}}");
        var response = ShellExecutionService.execute(command);

        String status = (response.isSuccess() && !response.output().isEmpty())
                ? response.output()
                : "Offline / Exited";

        String text = "📦 <b>Container:</b> <code>" + name + "</code>\n🕒 <b>Status:</b> " + status;

        InlineKeyboardMarkup markup = new InlineKeyboardMarkup();
        List<List<InlineKeyboardButton>> rows = new ArrayList<>();

        InlineKeyboardButton restartBtn = new InlineKeyboardButton();
        restartBtn.setText("🔄 Restart");
        restartBtn.setCallbackData("/docker restart " + name);

        InlineKeyboardButton logsBtn = new InlineKeyboardButton();
        logsBtn.setText("📄 Logs");
        logsBtn.setCallbackData("/docker logs " + name);

        InlineKeyboardButton backBtn = new InlineKeyboardButton();
        backBtn.setText("🔙 Back to List");
        backBtn.setCallbackData(BotCommands.DOCKER_MANAGER);

        rows.add(List.of(restartBtn, logsBtn));
        rows.add(List.of(backBtn));

        markup.setKeyboard(rows);

        if (messageId != null) {
            ctx.edit(messageId, text, markup);
        } else {
            ctx.reply(text, markup);
        }
    }

    private void restartContainer(CommandContext ctx, String containerName, Integer messageId) {
        String waitMsg = "🐳 Restarting <code>" + containerName + "</code>... ⏳";
        if (messageId != null) {
            ctx.edit(messageId, waitMsg, null);
        } else {
            messageId = ctx.reply(waitMsg);
        }

        var response = ShellExecutionService.execute(List.of("docker", "restart", containerName));

        if (response.isSuccess()) {
            ctx.edit(messageId, "✅ Container <code>" + containerName + "</code> successfully restarted.");
        } else {
            ctx.edit(messageId, "❌ Failed to restart <code>" + containerName + "</code>.\n<pre>" + response.error() + "</pre>");
        }
    }

    private void fetchLogs(CommandContext ctx, String containerName, int lines, String format) {
        var command = List.of("docker", "logs", "--tail", String.valueOf(lines), containerName);
        var response = ShellExecutionService.execute(command);

        if (!response.isSuccess()) {
            ctx.reply("❌ <b>Failed to fetch logs for</b> <code>" + containerName + "</code>:\n<pre>" + response.error() + "</pre>");
            return;
        }

        String logs = response.output().isEmpty() ? "[No recent logs found]" : response.output();
        boolean sendAsFile = format.equals("file") || (format.equals("auto") && logs.length() > 3800);

        if (sendAsFile) {
            try {
                Path tempFilePath = Files.createTempFile(containerName + "_logs_", ".txt");
                Files.writeString(tempFilePath, logs);

                String caption = "📄 <b>Logs for</b> <code>" + containerName + "</code> (Last " + lines + " lines)";
                ctx.sendDocument(caption, tempFilePath.toFile());

                if (!Files.deleteIfExists(tempFilePath)) {
                    log.warn("Failed to delete temp log file: {}", tempFilePath);
                }
            } catch (Exception e) {
                log.error("Failed to generate temp file", e);
                ctx.reply("❌ Failed to generate the log file.");
            }
        } else {
            if (logs.length() > 3800) {
                logs = logs.substring(logs.length() - 3800) + "\n\n[Truncated...]";
            }
            ctx.reply("📄 <b>Logs for</b> <code>" + containerName + "</code> (Last " + lines + " lines):\n<pre>" + logs + "</pre>");
        }
    }
}