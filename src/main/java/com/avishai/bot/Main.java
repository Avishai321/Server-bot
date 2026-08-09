package com.avishai.bot;

import com.avishai.bot.core.BotApplication;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class Main {
    private static final Logger log = LoggerFactory.getLogger(Main.class);

    public static void main(String[] args) {
        log.info("Starting Home Server Manager Bot...");
        try {
            BotApplication.start();
        } catch (Exception e) {
            log.error("Fatal error during application startup. Shutting down.", e);
            System.exit(1);
        }
    }
}
