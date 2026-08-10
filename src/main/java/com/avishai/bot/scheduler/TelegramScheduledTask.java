package com.avishai.bot.scheduler;

import com.avishai.bot.core.CommandContext;
import com.avishai.bot.core.Config;
import com.avishai.bot.core.MessageSender;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public abstract class TelegramScheduledTask implements ScheduledTask {
    protected final Logger log = LoggerFactory.getLogger(getClass());
    protected final MessageSender messageSender;
    protected final String adminChatId;

    public TelegramScheduledTask(MessageSender messageSender) {
        this.messageSender = messageSender;
        this.adminChatId = String.valueOf(Config.AUTHORIZED_CHAT_ID);
    }

    protected CommandContext createSystemContext(String virtualCommand) {
        return new CommandContext(virtualCommand, null, adminChatId, messageSender);
    }

    protected void notifyAdmin(String message) {
        messageSender.sendMessage(adminChatId, message);
    }

    @Override
    public final void run() {
        try {
            log.info("Executing scheduled task: {}", getTaskName());
            executeTask();
            log.info("Completed scheduled task: {}", getTaskName());
        } catch (Exception e) {
            log.error("Scheduled task '{}' crashed!", getTaskName(), e);
            notifyAdmin("⚠️ <b>Scheduled Task Crash</b>\nTask: <code>" +
                    getTaskName() + "</code>\nError: " + e.getMessage());
        }
    }

    protected abstract void executeTask() throws Exception;
}
