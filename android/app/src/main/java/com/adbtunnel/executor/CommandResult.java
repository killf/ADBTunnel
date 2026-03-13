package com.adbtunnel.executor;

public class CommandResult {
    public final int exitCode;
    public final String stdout;
    public final String stderr;
    public final long elapsedMs;

    public CommandResult(int exitCode, String stdout, String stderr, long elapsedMs) {
        this.exitCode = exitCode;
        this.stdout = stdout != null ? stdout : "";
        this.stderr = stderr != null ? stderr : "";
        this.elapsedMs = elapsedMs;
    }

    public static CommandResult error(String message) {
        return new CommandResult(-1, "", message, 0);
    }

    public String toJson() {
        return "{" +
            "\"exit_code\":" + exitCode + "," +
            "\"stdout\":" + jsonStr(stdout) + "," +
            "\"stderr\":" + jsonStr(stderr) + "," +
            "\"elapsed_ms\":" + elapsedMs +
            "}";
    }

    private static String jsonStr(String s) {
        if (s == null) return "\"\"";
        return "\"" + s
            .replace("\\", "\\\\")
            .replace("\"", "\\\"")
            .replace("\n", "\\n")
            .replace("\r", "\\r")
            .replace("\t", "\\t") + "\"";
    }
}
