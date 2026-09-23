package com.avishai.bot.handlers;

import com.avishai.bot.config.BotCommands;
import com.avishai.bot.routing.BotCommand;
import com.avishai.bot.routing.CommandContext;
import com.avishai.bot.services.SystemService;
import com.avishai.bot.util.TelegramUi;
import lombok.RequiredArgsConstructor;

import java.util.concurrent.ExecutorService;

@RequiredArgsConstructor
@BotCommand(
        command = {BotCommands.UPDATE_BOT},
        category = HandlerCategory.MONITORING_AND_ADMIN,
        description = "Recompile and restart bot",
        detailedHelp = "Pulls the latest code from git, recompiles via Maven, and restarts the daemon.",
        examples = {"/update"}
)
public class UpdateBotHandler implements CommandHandler {
    private final ExecutorService executorService;
    private final SystemService systemService;

    @Override
    public void handle(CommandContext ctx) {
        Integer msgId = ctx.reply("""
                <b>SYSTEM UPDATE</b>
                <b>Status:</b> <i>Compiling new source code with Maven...</i>""");

        executorService.submit(() -> {
            var response = systemService.pullAndRecompile();
            if (response.isSuccess()) {
                ctx.edit(msgId, """
                        <b>SYSTEM UPDATE</b>
                        <b>Status:</b> Compilation Successful!
                        
                        <i>Restarting daemon...</i>""");
                systemService.restartDaemon();
            } else {
                ctx.edit(msgId, String.format("""
                        <b>SYSTEM UPDATE</b>
                        <b>Status:</b> Compilation Failed
                        
                        <pre>%s</pre>""", TelegramUi.escapeHtml(response.error())));
            }
        });
    }
}
