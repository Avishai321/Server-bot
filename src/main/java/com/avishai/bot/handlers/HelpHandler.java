package com.avishai.bot.handlers;

import com.avishai.bot.config.BotCommands;
import com.avishai.bot.routing.BotCommand;
import com.avishai.bot.routing.CommandContext;
import lombok.RequiredArgsConstructor;

import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@RequiredArgsConstructor
@BotCommand(
        command = {BotCommands.HELP, BotCommands.START},
        category = HandlerCategory.MONITORING_AND_ADMIN,
        description = "Show control menu",
        detailedHelp = "Displays the system menu or detailed documentation.",
        arguments = {"[command]"},
        examples = {"/help", "/help sysinfo"}
)
public class HelpHandler implements CommandHandler {
    private final List<CommandHandler> registeredHandlers;

    @Override
    public void handle(CommandContext ctx) {
        String action = ctx.getActionData();
        String[] parts = action.split("\\s+");

        if (parts.length > 1) sendDetailedHelp(ctx, parts[1].toLowerCase());
        else sendGeneralHelp(ctx);
    }

    private void sendGeneralHelp(CommandContext ctx) {
        Map<HandlerCategory, List<CommandHandler>> groupedHandlers = registeredHandlers.stream()
                .filter(h -> {
                    if (!h.getClass().isAnnotationPresent(BotCommand.class)) return false;
                    return !h.getClass().getAnnotation(BotCommand.class).description().isBlank();
                })
                .collect(Collectors.groupingBy(
                        h -> h.getClass().getAnnotation(BotCommand.class).category()
                ));

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
                .map(h -> {
                    BotCommand ann = h.getClass().getAnnotation(BotCommand.class);
                    return ann.command()[0] + " - " + ann.description();
                })
                .collect(Collectors.joining("\n"));

        return header + commands + "\n";
    }

    private void sendDetailedHelp(CommandContext ctx, String topic) {
        String searchTarget = "/" + topic.replace("/", "");

        registeredHandlers.stream()
                .filter(h -> {
                    if (!h.getClass().isAnnotationPresent(BotCommand.class)) return false;
                    BotCommand ann = h.getClass().getAnnotation(BotCommand.class);

                    return Arrays.asList(ann.command()).contains(searchTarget)
                            || Arrays.asList(ann.command()).contains(topic);
                })
                .findFirst()
                .ifPresentOrElse(
                        handler -> {
                            BotCommand ann = handler.getClass().getAnnotation(BotCommand.class);

                            StringBuilder helpText = new StringBuilder();

                            helpText.append("<b>Command:</b> ")
                                    .append(ann.command()[0])
                                    .append("\n");

                            helpText.append("<b>Description:</b> ")
                                    .append(ann.description())
                                    .append("\n\n");

                            helpText.append(ann.detailedHelp())
                                    .append("\n\n");

                            if (ann.arguments().length > 0) {
                                helpText.append("<b>Arguments:</b>\n");
                                for (String arg : ann.arguments()) {
                                    helpText.append("• <code>").append(arg).append("</code>\n");
                                }
                                helpText.append("\n");
                            }

                            if (ann.examples().length > 0) {
                                helpText.append("<b>Examples:</b>\n");
                                for (String ex : ann.examples()) {
                                    helpText.append("• <code>").append(ex).append("</code>\n");
                                }
                            }
                            ctx.reply(helpText.toString());
                        },
                        () -> ctx.reply(
                                "⚠️ No detailed documentation found for that topic. " +
                                        "Type /help for the main menu."
                        )
                );
    }
}
