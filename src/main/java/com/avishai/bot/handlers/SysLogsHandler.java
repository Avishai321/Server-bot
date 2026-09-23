package com.avishai.bot.handlers;

import com.avishai.bot.config.Config;
import com.avishai.bot.routing.BotCommand;
import com.avishai.bot.routing.CommandContext;
import com.avishai.bot.util.ShellUtil;
import com.avishai.bot.util.TelegramUi;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;

@Slf4j
@RequiredArgsConstructor
@BotCommand(
        command = {"/syslogs"},
        category = HandlerCategory.MONITORING_AND_ADMIN,
        description = "Fetch bot journalctl logs",
        detailedHelp = "Fetch background journalctl logs for the bot daemon." +
                "\nUse 'live' argument for a secure streaming web link.",
        arguments = {"[lines]", "live [lines]"},
        examples = {"/syslogs", "/syslogs 100", "/syslogs live", "/syslogs live 200"}
)
public class SysLogsHandler implements CommandHandler {
    private final ExecutorService executorService;

    @Override
    public void handle(CommandContext ctx) {
        String[] parts = ctx.getActionData().split("\\s+");

        if (parts.length > 1 && parts[1].equalsIgnoreCase("live")) {
            int lines = parts.length > 2
                    ? parseInteger(parts[2], 50)
                    : 50;

            String url = String.format("http://%s:%d/logs/live?lines=%d",
                    Config.TAILSCALE_IP, Config.WEB_PORT, lines
            );

            ctx.reply(String.format("""
                    <b>LIVE TELEMETRY STREAM</b>
                    
                    Click the secure Tailnet link below to open the console:
                    %s""", url)
            );
        } else {
            int lines = parts.length > 1
                    ? parseInteger(parts[1], 20)
                    : 20;
            Integer msgId = ctx.reply("<i>Fetching daemon logs...</i>");

            CompletableFuture.runAsync(() -> fetchStaticLogs(ctx, msgId, lines), executorService)
                    .exceptionally(ex -> {
                        log.error("Failed to fetch static syslogs", ex);
                        ctx.edit(msgId, "<b>System Fault:</b>\n" + ex.getMessage());
                        return null;
                    });
        }
    }

    private void fetchStaticLogs(CommandContext ctx, Integer msgId, int lines) {
        var response = ShellUtil.execute(List.of(
                "journalctl", "-u", "telegram-bot", "-n", String.valueOf(lines), "--no-pager"
        ));

        if (!response.isSuccess()) {
            ctx.edit(msgId, String.format("<b>Failed to fetch logs:</b>\n<pre>%s</pre>",
                    TelegramUi.escapeHtml(response.error())));
            return;
        }

        String logs = response.output().isEmpty()
                ? "[No recent logs found]"
                : response.output();

        if (logs.length() > 1500) {
            logs = logs.substring(logs.length() - 1500)
                    + "\n\n[Truncated...]";
        }

        ctx.edit(msgId, String.format(
                "<b>SYSTEM LOGS</b> (Last %d lines):\n<pre>%s</pre>",
                lines, TelegramUi.escapeHtml(logs))
        );
    }

    private int parseInteger(String value, int fallback) {
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException e) {
            return fallback;
        }
    }
}
