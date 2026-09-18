package com.avishai.bot.handlers;

import com.avishai.bot.config.BotCommands;
import com.avishai.bot.routing.CommandContext;
import lombok.RequiredArgsConstructor;

import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@RequiredArgsConstructor
public class HelpHandler implements CommandHandler {
    private final List<CommandHandler> registeredHandlers;

    @Override
    public List<String> getCommandSignature() {
        return List.of(BotCommands.HELP, BotCommands.START);
    }

    @Override
    public HandlerCategory getCategory() {
        return HandlerCategory.MONITORING_AND_ADMIN;
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
        // Group handlers by the Enum
        Map<HandlerCategory, List<CommandHandler>> groupedHandlers = registeredHandlers.stream()
                .filter(h -> !h.getDescription().isEmpty())
                .collect(Collectors.groupingBy(CommandHandler::getCategory));

        String body = Arrays.stream(HandlerCategory.values())
                .filter(groupedHandlers::containsKey)
                .map(category -> buildCategorySection(
                        category,
                        groupedHandlers.get(category))
                )
                .collect(Collectors.joining("\n"));

        ctx.reply("<b>Home Server Manager</b>\n\n" + body +
                "\nType <code>/help [command]</code> for advanced syntax.");
    }

    private String buildCategorySection(HandlerCategory category, List<CommandHandler> handlers) {
        String header = "<b>" + category.getDisplayName() + "</b>\n";

        String commands = handlers.stream()
                .map(h -> h.getCommandSignature().get(0)
                        + " - "
                        + h.getDescription()
                )
                .collect(Collectors.joining("\n"));

        return header + commands + "\n";
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
                        () -> ctx.reply(
                                "⚠️ No detailed documentation found for that topic. " +
                                        "Type /help for the main menu."
                        )
                );
    }
}
