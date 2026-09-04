package com.avishai.bot.services;

import java.util.List;

public class DockerService {
    public String[] listContainers() {
        var response = ShellExecutionService.execute(List.of(
                "docker", "ps", "--format", "{{.Names}}")
        );

        return response.isSuccess()
                ? response.output().split("\n")
                : new String[0];
    }

    public String getContainerStatus(String name) {
        var response = ShellExecutionService.execute(List.of(
                "docker", "ps", "--filter",
                "name=^/" + name + "$", "--format", "{{.Status}}")
        );

        return (response.isSuccess() && !response.output().isEmpty())
                ? response.output() :
                "Offline / Exited";
    }

    public ShellExecutionService.ShellResponse restartContainer(String name) {
        return ShellExecutionService.execute(List.of("docker", "restart", name));
    }

    public ShellExecutionService.ShellResponse getLogs(String name, int lines) {
        return ShellExecutionService.execute(List.of(
                "docker", "logs", "--tail", String.valueOf(lines), name)
        );
    }
}
