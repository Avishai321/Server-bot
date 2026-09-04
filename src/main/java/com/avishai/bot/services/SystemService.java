package com.avishai.bot.services;

import com.avishai.bot.config.Config;
import lombok.extern.slf4j.Slf4j;

import java.io.File;
import java.util.List;

@Slf4j
public class SystemService {
    public String getRamUsage() {
        var data = ShellExecutionService.execute(List.of(
                "bash", "-c", "free -h | grep Mem | awk '{print $3 \" / \" $2}'")
        );

        return data.isSuccess() ? data.output() : "Error reading RAM";
    }

    public String getDiskUsage() {
        var data = ShellExecutionService.execute(List.of(
                "bash", "-c",
                "df -h / | tail -1 | awk '{print $3 \" / \" $2 \" (\"$5\")\"}'")
        );

        return data.isSuccess() ? data.output() : "Error reading Disk";
    }

    public String getUptime() {
        var data = ShellExecutionService.execute(List.of("uptime", "-p"));

        return data.isSuccess()
                ? data.output().replace("up ", "")
                : "Unknown";
    }

    public ShellExecutionService.ShellResponse pullAndRecompile() {
        return ShellExecutionService.execute(List.of(
                "bash", "-c", "mvn clean package"),
                new File(Config.PROJECT_PATH)
        );
    }

    public void restartDaemon() {
        log.info("Initiating application termination for systemd restart.");
        System.exit(0);
    }
}
