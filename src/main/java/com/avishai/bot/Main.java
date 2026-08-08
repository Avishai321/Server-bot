package com.avishai.bot;

public class Main {
    public static void main(String[] args) {
        String botUsername = "HomeServerManager_bot";
        String botToken = System.getenv("BOT_TOKEN");

        if (botToken == null || botToken.isEmpty()) {
            System.err.println("Could not load bot token");
            System.exit(1);
        }
    }
}
