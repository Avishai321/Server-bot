package com.avishai.bot.scheduler;

import java.time.Duration;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.concurrent.TimeUnit;

public interface BotTask extends Runnable {
    String getName();
    long getInitialDelay();
    long getPeriod();
    TimeUnit getTimeUnit();

    default long calculateDelayToHour(int targetHour) {
        ZoneId zone = ZoneId.of("Asia/Jerusalem");
        ZonedDateTime now = ZonedDateTime.now(zone);
        ZonedDateTime nextRun = now.withHour(targetHour)
                .withMinute(0)
                .withSecond(0)
                .withNano(0);

        if (now.compareTo(nextRun) > 0) {
            nextRun = nextRun.plusDays(1);
        }

        return Duration.between(now, nextRun).getSeconds();
    }
}
