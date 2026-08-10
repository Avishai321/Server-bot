package com.avishai.bot.handlers.commands;

import com.avishai.bot.core.CommandContext;
import com.avishai.bot.utils.BotCommands;
import java.util.Arrays;
import java.util.List;

public class HelpHandler implements CommandHandler {

    @Override
    public List<String> getCommandSignature() {
        return Arrays.asList(BotCommands.START, BotCommands.HELP);
    }

    @Override
    public void handle(CommandContext ctx) {
        String helpText = """
                🤖 <b>Home Server Manager</b>
                ━━━━━━━━━━━━━━━━━━
                Select a command below or use the native Menu button in the bottom left.
                
                🎧 <b>Media & Storage</b>
                • /spotisync - Start Spotify to Nextcloud synchronization
                
                🐳 <b>Infrastructure</b>
                • /docker - View running containers and restart services
                
                📊 <b>Monitoring & Admin</b>
                • /sysinfo - View hardware health and system metrics
                • /updatebot - Pull latest code, recompile, and restart daemon
                • /help - Display this command dashboard
                """;

        ctx.reply(helpText);
    }
}
