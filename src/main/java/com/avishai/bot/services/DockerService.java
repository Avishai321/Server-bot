package com.avishai.bot.services;

import com.avishai.bot.util.ShellUtil;

import java.util.List;

public class DockerService {
    public String[] listContainers() {
        var response = ShellUtil.execute(List.of(
                "docker", "ps", "--format", "{{.Names}}")
        );

        return response.isSuccess()
                ? response.output().split("\n")
                : new String[0];
    }

    public String getContainerStatus(String name) {
        var response = ShellUtil.execute(List.of(
                "docker", "ps", "--filter",
                "name=^/" + name + "$", "--format", "{{.Status}}")
        );

        return (response.isSuccess() && !response.output().isEmpty())
                ? response.output() :
                "Offline / Exited";
    }

    public ShellUtil.ShellResponse restartContainer(String name) {
        return ShellUtil.execute(List.of("docker", "restart", name));
    }

    public ShellUtil.ShellResponse getLogs(String name, int lines) {
        return ShellUtil.execute(List.of(
                "docker", "logs", "--tail", String.valueOf(lines), name)
        );
    }
}
