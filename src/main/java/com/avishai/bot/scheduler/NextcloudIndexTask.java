package com.avishai.bot.scheduler;

import com.avishai.bot.config.Config;
import com.avishai.bot.routing.MessageSender;
import com.avishai.bot.services.NextcloudService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import java.nio.file.Paths;
import java.util.concurrent.TimeUnit;

@Slf4j
@RequiredArgsConstructor
public class NextcloudIndexTask implements BotTask {
    private final NextcloudService nextcloudService;
    private final MessageSender bot;

    @Override
    public String getName() {
        return "Nextcloud Auto-Index";
    }

    @Override
    public long getInitialDelay() {
        return calculateDelayToHour(2);
    }

    @Override
    public long getPeriod() {
        return 3;
    }

    @Override
    public TimeUnit getTimeUnit() {
        return TimeUnit.DAYS;
    }

    @Override
    public void run() {
        if (nextcloudService.isBusy()) {
            log.warn("NextcloudIndexTask skipped: Service is currently busy.");
            return;
        }

        log.info("Triggering scheduled Nextcloud index scan.");
        bot.sendMessage(
                Config.AUTHORIZED_CHAT_ID_STR,
                "<b>Automated System Event</b>" +
                        "\nStarting routine Nextcloud background scan..."
        );

        var result = nextcloudService.runOccScan(Paths.get(NextcloudService.ROOT_PATH_STR));

        if (result.exitCode() != 0) {
            log.error("Scheduled Nextcloud scan failed with code {}: {}",
                    result.exitCode(), result.output()
            );

            bot.sendMessage(
                    Config.AUTHORIZED_CHAT_ID_STR,
                    "<b>Scan Failed</b>" +
                            "\nNextcloud routine scan exited with code " + result.exitCode()
            );
        }
    }
}
