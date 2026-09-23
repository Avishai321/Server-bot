package com.avishai.bot.handlers;

import com.avishai.bot.config.BotCommands;
import com.avishai.bot.routing.BotCommand;
import com.avishai.bot.routing.CommandContext;
import com.avishai.bot.services.SystemService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;

@Slf4j
@RequiredArgsConstructor
@BotCommand(
        command = {BotCommands.SYS_INFO},
        category = HandlerCategory.MONITORING_AND_ADMIN,
        description = "System hardware health",
        detailedHelp = "Displays real-time hardware telemetry such as RAM, CPU, and disk usage.",
        examples = {"/sysinfo"}
)
public class SysInfoHandler implements CommandHandler {
    private final ExecutorService executorService;
    private final SystemService systemService;

    @Override
    public void handle(CommandContext ctx) {
        Integer msgId = ctx.reply("<i>Gathering hardware telemetry...</i>");

        CompletableFuture.runAsync(() -> {
            String uiCard = String.format("""
                            <b>SERVER HEALTH DASHBOARD</b>
                            
                            <b>Uptime:</b> <code>%s</code>
                            <b>Memory (RAM):</b> <code>%s</code>
                            <b>Storage (Root):</b> <code>%s</code>
                            
                            <i>All systems operational.</i>""",
                    systemService.getUptime(),
                    systemService.getRamUsage(),
                    systemService.getDiskUsage()
            );
            ctx.edit(msgId, uiCard);
        }, executorService).exceptionally(ex -> {
            log.error("Failed to fetch system info", ex);
            ctx.edit(msgId, "<b>System Fault:</b>\n" + ex.getMessage());
            return null;
        });
    }
}
