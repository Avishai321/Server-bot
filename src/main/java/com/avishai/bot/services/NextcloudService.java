package com.avishai.bot.services;

import lombok.extern.slf4j.Slf4j;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

@Slf4j
public class NextcloudService {
    public static final String ROOT_PATH_STR = "/mnt/d/data";

    private final AtomicBoolean isIndexing = new AtomicBoolean(false);
    private volatile Process currentProcess = null;

    public boolean isBusy() {
        return isIndexing.get();
    }

    public NextcloudSyncResult runOccScan(Path targetPath) {
        if (!isIndexing.compareAndSet(false, true)) {
            return new NextcloudSyncResult(-1, "Process already running.");
        }

        try {
            String occCommand = formatOccCommand(targetPath);
            List<String> command = List.of(
                    "docker", "exec", "--user", "www-data",
                    "nextcloud-server-app-1", "bash", "-c", occCommand
            );

            log.info("Executing OCC Command: {}", occCommand);

            ProcessBuilder pb = new ProcessBuilder(command);
            pb.redirectErrorStream(true);
            currentProcess = pb.start();

            String rawOutput = new String(
                    currentProcess.getInputStream().readAllBytes(),
                    StandardCharsets.UTF_8
            );

            int exitCode = currentProcess.waitFor();
            String cleanOutput = rawOutput.replaceAll("\u001B\\[[;\\d]*m", "").trim();

            return new NextcloudSyncResult(exitCode, cleanOutput);

        } catch (Exception e) {
            log.error("OCC Scan failed", e);
            return new NextcloudSyncResult(-1, e.getMessage());
        } finally {
            currentProcess = null;
            isIndexing.set(false);
        }
    }

    public void abortScan() {
        if (!isIndexing.get() || currentProcess == null) return;

        try {
            new ProcessBuilder(
                    "docker", "exec", "--user", "www-data",
                    "nextcloud-server-app-1", "pkill", "-f", "occ files:scan"
            ).start().waitFor();

            currentProcess.destroyForcibly();
        } catch (Exception e) {
            log.error("Failed to kill container process", e);
        }
    }

    private String formatOccCommand(Path targetPath) {
        String targetStr = targetPath.toAbsolutePath().toString();

        if (targetStr.equals(ROOT_PATH_STR) || !targetStr.startsWith(ROOT_PATH_STR)) {
            return "php occ files:scan --all";
        }

        String relativePath = targetStr.substring(ROOT_PATH_STR.length());
        if (relativePath.startsWith("/")) {
            relativePath = relativePath.substring(1);
        }

        return "php occ files:scan --path=\"" + relativePath + "\"";
    }

    public record NextcloudSyncResult(int exitCode, String output) {}
}
