package com.avishai.bot.core;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedReader;
import java.io.File;
import java.io.InputStreamReader;
import java.util.List;
import java.util.function.Consumer;

public class ShellExecutionService {
    private static final Logger log = LoggerFactory.getLogger(ShellExecutionService.class);

    public static void executeStream(List<String> command, Consumer<String> onOutput, Consumer<Integer> onComplete) {
        try {
            Process process = new ProcessBuilder(command)
                    .redirectErrorStream(true)
                    .start();

            try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
                reader.lines().forEach(onOutput);
            }

            int exitCode = process.waitFor();
            if (onComplete != null) onComplete.accept(exitCode);
        } catch (Exception e) {
            log.error("Stream execution failed for command: {}", String.join(" ", command), e);
            if (onOutput != null) onOutput.accept("Critical Error: " + e.getMessage());
            if (onComplete != null) onComplete.accept(-1);
        }
    }

    public static ShellResponse execute(List<String> command, File directory) {
        StringBuilder output = new StringBuilder();
        StringBuilder error = new StringBuilder();

        try {
            ProcessBuilder pb = new ProcessBuilder(command);
            if (directory != null) {
                pb.directory(directory);
            }

            Process process = pb.start();

            try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    output.append(line).append("\n");
                }
            }

            try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getErrorStream()))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    error.append(line).append("\n");
                }
            }

            int exitCode = process.waitFor();
            return new ShellResponse(exitCode, output.toString().trim(), error.toString().trim());
        } catch (Exception e) {
            log.error("Shell execution failed for command: {}", String.join(" ", command), e);
            return new ShellResponse(-1, "", e.getMessage());
        }
    }

    public static ShellResponse execute(List<String> command) {
        return execute(command, null);
    }

    public record ShellResponse(int exitCode, String output, String error) {
        public boolean isSuccess() {
            return exitCode == 0;
        }
    }
}
