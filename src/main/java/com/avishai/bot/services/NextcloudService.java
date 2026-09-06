package com.avishai.bot.services;

import lombok.extern.slf4j.Slf4j;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

@Slf4j
public class NextcloudService {
    public static final String ROOT_PATH_STR = "/mnt/d/data/Avishai/files";

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

            // Trick: Pipe to cat to force non-interactive mode and disable the messy progress bar.
            // pipefail ensures we don't lose the exit code if the occ command crashes.
            List<String> command = List.of(
                    "docker", "exec", "--user", "www-data",
                    "nextcloud-server-app-1", "bash", "-c",
                    "set -o pipefail; " + occCommand + " | cat"
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

            // Fix: Broaden regex to strip ALL ANSI cursor movements, not just color codes
            String cleanOutput = rawOutput.replaceAll("\u001B\\[[;\\d]*[a-zA-Z]", "").trim();

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

        // Nextcloud requires the path format: user_id/files/path
        String nextcloudPath = "Avishai/files";
        if (!relativePath.isEmpty()) {
            nextcloudPath += "/" + relativePath;
        }

        return "php occ files:scan --path=\"" + nextcloudPath + "\"";
    }

    public record NextcloudSyncResult(int exitCode, String output) {}
}
