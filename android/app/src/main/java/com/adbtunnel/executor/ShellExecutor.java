package com.adbtunnel.executor;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

public class ShellExecutor {

    public CommandResult execute(String command, int timeoutSeconds) throws Exception {
        if (command == null || command.isEmpty()) {
            return CommandResult.error("empty command");
        }
        if (timeoutSeconds <= 0) timeoutSeconds = 30;

        long startMs = System.currentTimeMillis();

        Process process = new ProcessBuilder("sh", "-c", command)
            .redirectErrorStream(false)
            .start();

        StringBuilder stdout = new StringBuilder();
        StringBuilder stderr = new StringBuilder();

        Thread stdoutThread = new Thread(() -> {
            try (BufferedReader r = new BufferedReader(
                    new InputStreamReader(process.getInputStream()))) {
                char[] buf = new char[4096];
                int n;
                while ((n = r.read(buf)) != -1) stdout.append(buf, 0, n);
            } catch (IOException ignored) {}
        });
        Thread stderrThread = new Thread(() -> {
            try (BufferedReader r = new BufferedReader(
                    new InputStreamReader(process.getErrorStream()))) {
                char[] buf = new char[4096];
                int n;
                while ((n = r.read(buf)) != -1) stderr.append(buf, 0, n);
            } catch (IOException ignored) {}
        });

        stdoutThread.start();
        stderrThread.start();

        boolean finished = process.waitFor(timeoutSeconds, TimeUnit.SECONDS);
        if (!finished) {
            process.destroyForcibly();
            throw new TimeoutException("command timed out after " + timeoutSeconds + "s");
        }

        stdoutThread.join(2000);
        stderrThread.join(2000);

        return new CommandResult(
            process.exitValue(),
            stdout.toString(),
            stderr.toString(),
            System.currentTimeMillis() - startMs
        );
    }
}
