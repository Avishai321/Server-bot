package com.avishai.bot.handlers.commands;

import com.avishai.bot.core.CommandContext;
import com.avishai.bot.core.Config;
import com.avishai.bot.core.ShellExecutionService;
import com.avishai.bot.utils.BotCommands;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class UpdateBotHandler implements CommandHandler {
    private static final Logger log = LoggerFactory.getLogger(UpdateBotHandler.class);
    private final ExecutorService executorService = Executors.newSingleThreadExecutor();

    @Override
    public List<String> getCommandSignature() {
        return List.of(BotCommands.UPDATE_BOT);
    }

    @Override
    public void handle(CommandContext ctx) {
        Integer messageId = ctx.reply(
                "🛠️ <b>System Update</b>\n" +
                        "Status: <i>Compiling new source code with Maven...</i> ⏳"
        );

        executorService.submit(() -> {
            String projectPath = Config.PROJECT_PATH;

            var response = ShellExecutionService.execute(
                    List.of("bash", "-c", "mvn clean package"),
                    new File(projectPath)
            );

            if (response.isSuccess()) {
                ctx.edit(messageId,
                        """
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
