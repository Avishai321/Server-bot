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
        String fullCommand = "";

        if (ctx.update().hasCallbackQuery()) {
            fullCommand = ctx.update().getCallbackQuery().getData();
        } else if (ctx.update().hasMessage() && ctx.update().getMessage().hasText()) {
            fullCommand = ctx.update().getMessage().getText();
        }

        if (fullCommand.startsWith("/docker restart ")) {
            String containerName = fullCommand.replace("/docker restart ", "").trim();
            executorService.submit(() -> restartContainer(ctx, containerName));
            return;
        } else if (fullCommand.startsWith("/docker logs ")) {

            // Parse arguments: /docker logs <container> [lines] [format]
            String[] parts = fullCommand.split("\\s+");

            if (parts.length >= 3) {
                String containerName = parts[2];
                int lines = 20;           // Default value
                String format = "auto";   // Default value

                // Override lines if provided
                if (parts.length >= 4) {
                    try {
                        lines = Integer.parseInt(parts[3]);
                    } catch (NumberFormatException ignored) {
                        log.warn("Invalid line number provided, falling back to default.");
                    }
                }

                // Override format if provided
                if (parts.length >= 5) {
                    format = parts[4].toLowerCase();
                }

                // Variables must be effectively final for lambda injection
                int finalLines = lines;
                String finalFormat = format;

                executorService.submit(() -> fetchLogs(ctx, containerName, finalLines, finalFormat));
                return;
            }
        }

        executorService.submit(() -> listContainers(ctx));
    }

    private void listContainers(CommandContext ctx) {
        var response = ShellExecutionService.execute(List.of("docker", "ps", "--format", "{{.Names}}|{{.Status}}"));
        if (!response.isSuccess()) {
            log.error("Docker PS failed: {}", response.error());
            ctx.reply("⚠️ Failed to reach Docker engine.\n<pre>" + response.error() + "</pre>");
            return;
        }

        StringBuilder sb = new StringBuilder("🐳 <b>Docker Containers</b>\n━━━━━━━━━━━━━━━━━━\n");
        InlineKeyboardMarkup markup = new InlineKeyboardMarkup();
        List<List<InlineKeyboardButton>> rows = new ArrayList<>();

        String[] lines = response.output().split("\n");
        for (String line : lines) {
            if (line.trim().isEmpty()) continue;
            String[] parts = line.split("\\|");
            if (parts.length == 2) {
                String name = parts[0];
                String status = parts[1];
                sb.append("📦 <code>").append(name).append("</code>\n");
                sb.append("🕒 ").append(status).append("\n\n");
                rows.add(getContainerKeyboardRow(name));
            }
        }
        markup.setKeyboard(rows);
        ctx.reply(sb.toString(), markup);
    }

    private List<InlineKeyboardButton> getContainerKeyboardRow(String containerName) {
        InlineKeyboardButton restartBtn = new InlineKeyboardButton();
        restartBtn.setText("🔄 Restart");
        restartBtn.setCallbackData("/docker restart " + containerName);

        InlineKeyboardButton logsBtn = new InlineKeyboardButton();
        logsBtn.setText("📄 Logs");
        logsBtn.setCallbackData("/docker logs " + containerName);

        List<InlineKeyboardButton> row = new ArrayList<>();
        row.add(restartBtn);
        row.add(logsBtn);
        return row;
    }

    private void restartContainer(CommandContext ctx, String containerName) {
        Integer msgId = ctx.reply("🐳 Restarting <code>" + containerName + "</code>... ⏳");

        var response = ShellExecutionService.execute(List.of("docker", "restart", containerName));
        if (response.isSuccess()) ctx.edit(
                msgId,
                "✅ Container <code>" + containerName + "</code> successfully restarted."
        );
        else ctx.edit(
                msgId,
                "❌ Failed to restart <code>" + containerName +
                        "</code>.\n<pre>" + response.error() + "</pre>"
        );
    }

    private void fetchLogs(CommandContext ctx, String containerName, int lines, String format) {
        var response = ShellExecutionService.execute(List.of(
                "docker",
                "logs",
                "--tail",
                String.valueOf(lines),
                containerName)
        );

        if (response.isSuccess()) {
            String logs = response.output();
            if (logs.isEmpty()) {
                logs = "[No recent logs found or container is silent]";
            }

            boolean sendAsFile = format.equals("file") || (format.equals("auto") && logs.length() > 3800);

            if (sendAsFile) {
                try {
                    Path tempFilePath = Files.createTempFile(containerName + "_logs_", ".txt");
                    Files.writeString(tempFilePath, logs);

                    ctx.sendDocument("📄 <b>Logs for</b> <code>" + containerName +
                            "</code> (Last " + lines + " lines)", tempFilePath.toFile());

                    if (!Files.deleteIfExists(tempFilePath)) {
                        log.warn("Failed to delete temporary log file: {}", tempFilePath.toAbsolutePath());
                    }

                } catch (Exception e) {
                    log.error("Failed to generate temporary log file for {}", containerName, e);
                    ctx.reply("❌ Failed to generate the log file.");
                }
            } else {
                if (logs.length() > 3800) {
                    logs = logs.substring(logs.length() - 3800) + "\n\n[Truncated...]";
                }
                ctx.reply("📄 <b>Logs for</b> <code>" + containerName +
                        "</code> (Last " + lines + " lines):\n<pre>" + logs + "</pre>");
            }
        }
        else ctx.reply("❌ <b>Failed to fetch logs for</b> <code>" +
                containerName + "</code>:\n<pre>" + response.error() + "</pre>");
    }
}
