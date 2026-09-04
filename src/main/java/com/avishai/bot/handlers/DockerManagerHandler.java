package com.avishai.bot.handlers;

import com.avishai.bot.config.BotCommands;
import com.avishai.bot.core.CommandContext;
import com.avishai.bot.services.DockerService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.InlineKeyboardMarkup;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.buttons.InlineKeyboardButton;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;

@Slf4j
@RequiredArgsConstructor
public class DockerManagerHandler implements CommandHandler {
    private final ExecutorService executorService;
    private final DockerService dockerService;

    @Override
    public List<String> getCommandSignature() {
        return List.of(BotCommands.DOCKER_MANAGER);
    }

    @Override
    public String getCategory() {
        return "🐳 Infrastructure";
    }

    @Override
    public String getDescription() {
        return "Manage Docker containers";
    }

    @Override
    public String getDetailedHelp() {
        return """
                🐳 <b>Docker Manager - Manual</b>
                
                <b>Interactive UI:</b>
                Type <code>/docker</code> to get clickable buttons.
                
                <b>Direct CLI Commands:</b>
                <code>/docker restart &lt;container&gt;</code>
                <code>/docker logs &lt;container&gt; [lines] [format]</code>""";
    }

    @Override
    public void handle(CommandContext ctx) {
        String action = ctx.getActionData();
        Integer msgId = ctx.getMessageId();

        if (action.equals(BotCommands.DOCKER_MANAGER)) {
            executorService.submit(() -> sendMainList(ctx, msgId));
        } else if (action.startsWith("/docker menu ")) {
            String name = action.replace("/docker menu ", "").trim();
            executorService.submit(() -> sendContainerMenu(ctx, name, msgId));
        } else if (action.startsWith("/docker restart ")) {
            String name = action.replace("/docker restart ", "").trim();
            executorService.submit(() -> restartContainer(ctx, name, msgId));
        } else if (action.startsWith("/docker logs ")) {
            String[] parts = action.split("\\s+");
            if (parts.length >= 3) {
                String name = parts[2];
                int lines = (parts.length >= 4) ? parseLinesArg(parts[3]) : 20;
                String format = (parts.length >= 5) ? parts[4].toLowerCase() : "auto";
                executorService.submit(() -> fetchLogs(ctx, name, lines, format));
            }
        }
    }

    private void sendMainList(CommandContext ctx, Integer messageId) {
        String[] containers = dockerService.listContainers();
        if (containers.length == 0) {
            ctx.reply("❌ Failed to fetch containers from Docker engine.");
            return;
        }

        InlineKeyboardMarkup markup = new InlineKeyboardMarkup();
        markup.setKeyboard(buildContainerGrid(containers));

        String text = "🐳 <b>Docker Management</b>\n\nSelect a container to manage:";
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
        if (!currentRow.isEmpty()) rows.add(currentRow);
        return rows;
    }

    private void sendContainerMenu(CommandContext ctx, String name, Integer messageId) {
        String status = dockerService.getContainerStatus(name);

        InlineKeyboardButton restartBtn = new InlineKeyboardButton();
        restartBtn.setText("🔄 Restart");
        restartBtn.setCallbackData("/docker restart " + name);

        InlineKeyboardButton logsBtn = new InlineKeyboardButton();
        logsBtn.setText("📄 Logs");
        logsBtn.setCallbackData("/docker logs " + name);

        InlineKeyboardButton backBtn = new InlineKeyboardButton();
        backBtn.setText("🔙 Back to List");
        backBtn.setCallbackData(BotCommands.DOCKER_MANAGER);

        InlineKeyboardMarkup markup = new InlineKeyboardMarkup();
        markup.setKeyboard(List.of(List.of(restartBtn, logsBtn), List.of(backBtn)));

        String text = String.format("""
                📦 <b>Container:</b> <code>%s</code>
                📊 <b>Status:</b> %s""", name, status);

        if (messageId != null) ctx.edit(messageId, text, markup);
        else ctx.reply(text, markup);
    }

    private void restartContainer(CommandContext ctx, String name, Integer messageId) {
        String waitMsg = String.format("🔄 Restarting <code>%s</code>...", name);
        if (messageId != null) ctx.edit(messageId, waitMsg, null);
        else messageId = ctx.reply(waitMsg);

        var response = dockerService.restartContainer(name);

        if (response.isSuccess()) ctx.edit(
                messageId,
                String.format("✅ Container <code>%s</code> successfully restarted.", name)
        );
        else ctx.edit(
                messageId,
                String.format("❌ Failed to restart <code>%s</code>.\n<pre>%s</pre>", name, response.error())
        );
    }

    private void fetchLogs(CommandContext ctx, String name, int lines, String format) {
        var response = dockerService.getLogs(name, lines);

        if (!response.isSuccess()) {
            ctx.reply(String.format("❌ <b>Failed to fetch logs for</b> <code>%s</code>:\n<pre>%s</pre>",
                    name, response.error()
            ));
            return;
        }

        String logs = response.output().isEmpty() ? "[No recent logs found]" : response.output();
        boolean sendAsFile = format.equals("file") || (format.equals("auto") && logs.length() > 3800);

        if (sendAsFile) {
            try {
                Path tempFilePath = Files.createTempFile(name + "_logs_", ".txt");
                Files.writeString(tempFilePath, logs);
                ctx.sendDocument(
                        String.format("📄 <b>Logs for</b> <code>%s</code> (Last %d lines)",
                                name,
                                lines),
                        tempFilePath.toFile()
                );
                Files.deleteIfExists(tempFilePath);
            } catch (Exception e) {
                log.error("Failed to generate temp file", e);
                ctx.reply("❌ Failed to generate the log file.");
            }
        } else {
            if (logs.length() > 3800) logs = logs.substring(logs.length() - 3800) + "\n\n[Truncated...]";
            ctx.reply(String.format(
                    "📄 <b>Logs for</b> <code>%s</code> (Last %d lines):\n<pre>%s</pre>",
                    name, lines, logs)
            );
        }
    }

    private int parseLinesArg(String value) {
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException e) {
            return 20;
        }
    }
}
