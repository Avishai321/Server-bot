package com.avishai.bot.handlers;

import com.avishai.bot.core.CommandContext;
import com.avishai.bot.config.Config;
import com.avishai.bot.services.ShellExecutionService;
import com.avishai.bot.config.BotCommands;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import java.io.File;
import java.util.List;
import java.util.concurrent.ExecutorService;

@Slf4j
@RequiredArgsConstructor
public class UpdateBotHandler implements CommandHandler {
    private final ExecutorService executorService;

    @Override
    public List<String> getCommandSignature() {
        return List.of(BotCommands.UPDATE_BOT);
    }

    @Override
    public void handle(CommandContext ctx) {
        Integer messageId = ctx.reply(
                "🛠️ <b>System Update</b>\n" +
                        "Status: <i>Compiling new source code with Maven...</i> ⏳");

        executorService.submit(() -> {
            String projectPath = Config.PROJECT_PATH;
            var response = ShellExecutionService.execute(
                    List.of("bash", "-c", "mvn clean package"),
                    new File(projectPath)
            );

            if (response.isSuccess()) {
                ctx.edit(messageId, """
                        🛠️ <b>System Update</b>
                        Status: Compilation Successful! ✅
                        <i>Restarting daemon... Be back in 3 seconds.</i>""");
                log.info("Self-update successful. Initiating suicide for systemd restart.");
                System.exit(0);
            } else ctx.edit(messageId,
                    "🛠️ <b>System Update</b>\n" +
                            "Status: Compilation Failed ❌\n" +
                            "<pre>" + response.error() + "</pre>");
        });
    }
}
