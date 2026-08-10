package com.avishai.bot.handlers.commands;

import com.avishai.bot.core.CommandContext;
import com.avishai.bot.core.MessageSender;
import com.avishai.bot.utils.BotCommands;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.InlineKeyboardMarkup;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.buttons.InlineKeyboardButton;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.List;

public class DockerManagerHandler implements CommandHandler {
    private static final Logger log = LoggerFactory.getLogger(DockerManagerHandler.class);

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
                restartContainer(
                        ctx.chatId(),
                        containerName,
                        ctx.messageSender(),
                        ctx.update().getCallbackQuery().getMessage().getMessageId()
                );
                return;
            }
        }

        listContainers(ctx);
    }

    private void listContainers(CommandContext ctx) {
        try {
            ProcessBuilder pb = new ProcessBuilder(
                    "docker",
                    "ps",
                    "--format",
                    "{{.Names}}|{{.Status}}"
            );
            Process process = pb.start();

            StringBuilder sb = new StringBuilder("🐳 <b>Docker Containers</b>\n━━━━━━━━━━━━━━━━━━\n");
            InlineKeyboardMarkup markup = new InlineKeyboardMarkup();
            List<List<InlineKeyboardButton>> rows = new ArrayList<>();

            try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    String[] parts = line.split("\\|");
                    if (parts.length == 2) {
                        String name = parts[0];
                        String status = parts[1];

                        sb.append("📦 <code>").append(name).append("</code>\n");
                        sb.append("🕒 ").append(status).append("\n\n");

                        InlineKeyboardButton btn = new InlineKeyboardButton();
                        btn.setText("🔄 Restart " + name);
                        btn.setCallbackData("/docker restart " + name);

                        List<InlineKeyboardButton> row = new ArrayList<>();
                        row.add(btn);
                        rows.add(row);
                    }
                }
            }

            markup.setKeyboard(rows);
            ctx.reply(sb.toString(), markup);
        } catch (Exception e) {
            log.error("Failed to list docker containers", e);
            ctx.reply("⚠️ Failed to reach Docker engine.");
        }
    }

    private void restartContainer(String chatId,
                                  String containerName,
                                  MessageSender messageSender,
                                  Integer messageId) {

        messageSender.editMessage(
                chatId,
                messageId,
                "🐳 Restarting <code>" + containerName + "</code>... ⏳",
                null
        );

        new Thread(() -> {
            try {
                ProcessBuilder pb = new ProcessBuilder("docker", "restart", containerName);
                int exitCode = pb.start().waitFor();

                if (exitCode == 0) messageSender.editMessage(
                        chatId,
                        messageId,
                        "✅ Container <code>" + containerName + "</code> successfully restarted."
                );
                else messageSender.editMessage(
                        chatId,
                        messageId,
                        "❌ Failed to restart <code>" + containerName + "</code>."
                );
            } catch (Exception e) {
                log.error("Docker restart error", e);
            }
        }).start();
    }
}
