package com.avishai.bot.handlers.commands;

import com.avishai.bot.core.CommandContext;
import com.avishai.bot.core.ShellExecutionService;
import com.avishai.bot.utils.BotCommands;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.InlineKeyboardMarkup;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.buttons.InlineKeyboardButton;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class DockerManagerHandler implements CommandHandler {
    private static final Logger log = LoggerFactory.getLogger(DockerManagerHandler.class);
    private final ExecutorService executorService = Executors.newCachedThreadPool();

    @Override
    public List<String> getCommandSignature() {
        return List.of(BotCommands.DOCKER_MANAGER);
    }

    @Override
    public void handle(CommandContext ctx) {
        if (ctx.update().hasCallbackQuery()) {
            String callbackData = ctx.update().getCallbackQuery().getData();

            if (callbackData.startsWith("/docker restart ")) {
                String containerName = callbackData.replace("/docker restart ", "");
                executorService.submit(() -> restartContainer(ctx, containerName));
                return;
            } else if (callbackData.startsWith("/docker logs ")) {
                String containerName = callbackData.replace("/docker logs ", "");
                executorService.submit(() -> fetchLogs(ctx, containerName));
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

        // Button 2: Logs
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

        if (response.isSuccess()) ctx.edit(msgId,
                "✅ Container <code>" + containerName + "</code> successfully restarted.");
        else ctx.edit(msgId,
                "❌ Failed to restart <code>" + containerName +
                        "</code>.\n<pre>" + response.error() + "</pre>");
    }

    private void fetchLogs(CommandContext ctx, String containerName) {
        var response = ShellExecutionService.execute(List.of("docker", "logs", "--tail", "20", containerName));

        if (response.isSuccess()) {
            String logs = response.output();
            if (logs.isEmpty()) {
                logs = "[No recent logs found or container is silent]";
            }

            // Telegram limits messages to 4096 characters. Truncate if necessary.
            if (logs.length() > 3800) logs = logs.substring(logs.length() - 3800);

            ctx.reply("📄 <b>Logs for</b> <code>" + containerName + "</code>:\n<pre>" + logs + "</pre>");
        } else ctx.reply("❌ <b>Failed to fetch logs for</b> <code>" +
                containerName + "</code>:\n<pre>" + response.error() + "</pre>");
    }
}
