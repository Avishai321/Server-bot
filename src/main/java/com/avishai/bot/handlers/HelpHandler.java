package com.avishai.bot.handlers;

import com.avishai.bot.config.BotCommands;
import com.avishai.bot.core.CommandContext;
import lombok.RequiredArgsConstructor;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@RequiredArgsConstructor
public class HelpHandler implements CommandHandler {
    private final List<CommandHandler> registeredHandlers;

    @Override
    public List<String> getCommandSignature() {
        return List.of(BotCommands.START, BotCommands.HELP);
    }

    @Override
    public String getCategory() {
        return "⚙️ Monitoring & Admin";
    }

    @Override
    public String getDescription() {
        return "Show control menu";
    }

    @Override
    public void handle(CommandContext ctx) {
        String action = ctx.getActionData();
        String[] parts = action.split("\\s+");

        if (parts.length > 1) sendDetailedHelp(ctx, parts[1].toLowerCase());
        else sendGeneralHelp(ctx);
    }

    private void sendGeneralHelp(CommandContext ctx) {
        // Group all handlers by their category dynamically
        Map<String, List<CommandHandler>> groupedHandlers = registeredHandlers.stream()
                .filter(h -> !h.getDescription().isEmpty())
                .collect(Collectors.groupingBy(CommandHandler::getCategory));

        StringBuilder helpText = new StringBuilder();
        helpText.append("""
                🤖 <b>Home Server Manager</b>
                
                Select a command below or use the native Menu button.
                
                """);

        // Render each category and its commands
        groupedHandlers.forEach((category, handlers) -> {
            helpText.append("<b>").append(category).append("</b>\n");
            for (CommandHandler handler : handlers) {
                // Use the first command signature as the primary display command
                helpText.append(handler.getCommandSignature().get(0))
                        .append(" - ")
                        .append(handler.getDescription())
                        .append("\n");
            }
            helpText.append("\n");
        });

        helpText.append("💡 <b>Pro Tip:</b> Type <code>/help [command]</code> for advanced syntax.");
        ctx.reply(helpText.toString());
    }

    private void sendDetailedHelp(CommandContext ctx, String topic) {
        String searchTarget = "/" + topic;

        registeredHandlers.stream()
                .filter(h -> h.getCommandSignature().contains(searchTarget) ||
                        h.getCommandSignature().contains(topic)
                )
                .findFirst()
                .ifPresentOrElse(
                        handler -> ctx.reply(handler.getDetailedHelp()),
                        () -> ctx.reply("⚠️ No detailed documentation found for that topic. " +
                                "Type /help for the main menu.")
                );
    }
}
