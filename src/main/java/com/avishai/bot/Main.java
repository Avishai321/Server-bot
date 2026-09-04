package com.avishai.bot;

import com.avishai.bot.core.BotApplication;
import lombok.extern.slf4j.Slf4j;

@Slf4j
public class Main {
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
