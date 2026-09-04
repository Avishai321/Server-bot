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
        String text = "";
        if (ctx.update().hasMessage() && ctx.update().getMessage().hasText()) {
            text = ctx.update().getMessage().getText();
        }

        String[] parts = text.split("\\s+");

        // For requests like "/help docker"
        if (parts.length > 1) sendDetailedHelp(ctx, parts[1].toLowerCase());
        else sendGeneralHelp(ctx);
    }

    private void sendGeneralHelp(CommandContext ctx) {
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
                
                ℹ️ <b>Pro Tip:</b> Type <code>/help docker</code> for advanced syntax.
                """;
        ctx.reply(helpText);
    }

    private void sendDetailedHelp(CommandContext ctx, String topic) {
        String response = switch (topic) {
            case "docker", "/docker" -> """
                    🐳 <b>Docker Manager - Manual</b>
                    ━━━━━━━━━━━━━━━━━━
                    <b>Interactive UI:</b>
                    Type <code>/docker</code> to get clickable buttons.
                    
                    <b>Direct CLI Commands:</b>
                    • <code>/docker restart &lt;container&gt;</code>
                    • <code>/docker logs &lt;container&gt; [lines] [format]</code>
                    
                    <b>Log Arguments (Optional):</b>
                    • <code>lines</code>: Amount of lines to fetch (Default: 20)
                    • <code>format</code>: Output mode. Use <code>text</code>, <code>file</code>, or <code>auto</code> (Default: auto)
                    
                    <b>Examples:</b>
                    <code>/docker logs nextcloud 100</code>
                    <code>/docker logs nextcloud 500 file</code>
                    """;
            case "spotisync", "/spotisync" -> """
                    🎧 <b>Spotify Sync - Manual</b>
                    ━━━━━━━━━━━━━━━━━━
                    Executes SpotDL via system shell and updates Nextcloud index.
                    
                    <b>Commands:</b>
                    • <code>/spotisync</code> - Start the sync process
                    • <code>/stop_spotisync</code> - Send SIGTERM to the process tree
                    """;
            default ->
                    "⚠️ No detailed documentation found for <code>" + topic + "</code>. Type /help for the main menu.";
        };
        ctx.reply(response);
    }
}
