package com.avishai.bot.handlers.commands;

import com.avishai.bot.core.CommandContext;
import com.avishai.bot.core.ShellExecutionService;
import com.avishai.bot.utils.BotCommands;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.concurrent.ExecutorService;

public class SysInfoHandler implements CommandHandler {
    private static final Logger log = LoggerFactory.getLogger(SysInfoHandler.class);
    private final ExecutorService executorService;

    public SysInfoHandler(ExecutorService executorService) {
        this.executorService = executorService;
    }

    @Override
    public List<String> getCommandSignature() {
        return List.of(BotCommands.SYS_INFO);
    }

    @Override
    public void handle(CommandContext ctx) {
        Integer msgId = ctx.reply("📊 <i>Gathering hardware telemetry...</i> ⏳");

        executorService.submit(() -> {
            try {
                var ramData = ShellExecutionService.execute(List.of(
                        "bash", "-c", "free -h | grep Mem | awk '{print $3 \" / \" $2}'"));

                var rootDiskData = ShellExecutionService.execute(List.of(
                        "bash", "-c", "df -h / | tail -1 | awk '{print $3 \" / \" $2 \" (\"$5\")\"}'"));

                var uptimeData = ShellExecutionService.execute(List.of("uptime", "-p"));

                String uiCard = """
                        💻 <b>Server Health Dashboard</b>
                        ━━━━━━━━━━━━━━━━━━
                        ⏱️ <b>Uptime:</b> <code>%s</code>
                        
                        🧠 <b>Memory (RAM):</b>
                        <code>%s</code>
                        
                        💾 <b>Storage (Root):</b>
                        <code>%s</code>
                        ━━━━━━━━━━━━━━━━━━
                        🟢 <i>All systems operational</i>
                        """.formatted(
                        uptimeData.isSuccess() ? uptimeData.output().replace("up ", "") : "Unknown",
                        ramData.isSuccess() ? ramData.output() : "Error reading RAM",
                        rootDiskData.isSuccess() ? rootDiskData.output() : "Error reading Disk"
                );

                ctx.edit(msgId, uiCard);

            } catch (Exception e) {
                log.error("Failed to fetch system info", e);
                ctx.edit(msgId, "⚠️ <b>Error fetching telemetry:</b>\n" + e.getMessage());
            }
        });
    }
}
