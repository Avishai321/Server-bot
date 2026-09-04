package com.avishai.bot.handlers;

import com.avishai.bot.config.BotCommands;
import com.avishai.bot.core.CommandContext;
import com.avishai.bot.services.SystemService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import java.util.List;
import java.util.concurrent.ExecutorService;

@Slf4j
@RequiredArgsConstructor
public class SysInfoHandler implements CommandHandler {
    private final ExecutorService executorService;
    private final SystemService systemService;

    @Override
    public List<String> getCommandSignature() {
        return List.of(BotCommands.SYS_INFO);
    }

    @Override
    public void handle(CommandContext ctx) {
        Integer msgId = ctx.reply("🔍 <i>Gathering hardware telemetry...</i>");

        executorService.submit(() -> {
            try {
                String uiCard = String.format("""
                        🖥️ <b>Server Health Dashboard</b>
                       \s
                        ⏱️ <b>Uptime:</b> <code>%s</code>
                       \s
                        🧠 <b>Memory (RAM):</b>\s
                        <code>%s</code>
                       \s
                        💾 <b>Storage (Root):</b>\s
                        <code>%s</code>
                       \s
                        <i>✅ All systems operational</i>""",
                        systemService.getUptime(),
                        systemService.getRamUsage(),
                        systemService.getDiskUsage()
                );
                ctx.edit(msgId, uiCard);
            } catch (Exception e) {
                log.error("Failed to fetch system info", e);
                ctx.edit(msgId, "❌ <b>Error fetching telemetry:</b>\n" + e.getMessage());
            }
        });
    }
}
