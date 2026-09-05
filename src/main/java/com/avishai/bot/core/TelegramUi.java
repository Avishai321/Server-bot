package com.avishai.bot.core;

import lombok.experimental.UtilityClass;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.InlineKeyboardMarkup;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.buttons.InlineKeyboardButton;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;

@UtilityClass
public class TelegramUi {
    public static InlineKeyboardButton button(String text, String callbackData) {
        InlineKeyboardButton button = new InlineKeyboardButton();
        button.setText(text);
        button.setCallbackData(callbackData);
        return button;
    }

    public static InlineKeyboardMarkup singleButtonKeyboard(String text, String callbackData) {
        InlineKeyboardMarkup markup = new InlineKeyboardMarkup();
        markup.setKeyboard(List.of(List.of(button(text, callbackData))));
        return markup;
    }

    public static <T> List<List<InlineKeyboardButton>> createGrid(
            List<T> items,
            int columns,
            Function<T, InlineKeyboardButton> buttonMapper
    ) {
        List<List<InlineKeyboardButton>> rows = new ArrayList<>();
        List<InlineKeyboardButton> currentRow = new ArrayList<>();

        for (T item : items) {
            currentRow.add(buttonMapper.apply(item));
            if (currentRow.size() == columns) {
                rows.add(currentRow);
                currentRow = new ArrayList<>();
            }
        }
        if (!currentRow.isEmpty()) rows.add(currentRow);
        return rows;
    }

    public static String escapeHtml(String text) {
        if (text == null) return "";
        return text.replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;");
    }

    public String progressBar(int percentage, int totalBars) {
        int filledBars = Math.max(0, Math.min((percentage * totalBars) / 100, totalBars));
        StringBuilder bar = new StringBuilder("[");
        for (int i = 0; i < totalBars; i++) {
            bar.append(i < filledBars ? "■" : "□");
        }
        bar.append("]");
        return bar.toString();
    }
}
