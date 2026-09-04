package com.avishai.bot.handlers;

import com.avishai.bot.config.BotCommands;
import com.avishai.bot.core.CommandContext;
import com.avishai.bot.services.SystemService;
import lombok.RequiredArgsConstructor;

import java.util.List;
import java.util.concurrent.ExecutorService;

@RequiredArgsConstructor
public class UpdateBotHandler implements CommandHandler {
    private final ExecutorService executorService;
    private final SystemService systemService;

    @Override
    public List<String> getCommandSignature() {
        return List.of(BotCommands.UPDATE_BOT);
    }

    @Override
    public String getCategory() {
        return "⚙️ Monitoring & Admin";
    }

    @Override
    public String getDescription() {
        return "Recompile and restart bot";
    }

    @Override
    public void handle(CommandContext ctx) {
        Integer msgId = ctx.reply("""
                🛠️ <b>System Update</b>
                Status: <i>Compiling new source code with Maven...</i>""");

        executorService.submit(() -> {
            var response = systemService.pullAndRecompile();

            if (response.isSuccess()) {
                ctx.edit(msgId, """
                        🛠️ <b>System Update</b>
                        Status: Compilation Successful! ✅
                        
                        <i>Restarting daemon... Be back in 3 seconds.</i>""");
                systemService.restartDaemon();
            } else {
                ctx.edit(msgId, String.format("""
                        ⚠️ <b>System Update</b>
                        Status: Compilation Failed ❌
                        
                        <pre>%s</pre>""", response.error()));
            }
        });
    }
}
