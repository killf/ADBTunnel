package com.adbtunnel.executor;

import android.content.Context;
import android.util.Base64;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.util.concurrent.TimeUnit;

public class ScreencapExecutor {

    private final Context context;

    public ScreencapExecutor(Context context) {
        this.context = context;
    }

    public CommandResult execute() throws Exception {
        long startMs = System.currentTimeMillis();
        File tmpFile = new File(context.getCacheDir(), "screencap_tmp.png");

        // Take screenshot to temp file
        Process p = Runtime.getRuntime().exec(
            new String[]{"sh", "-c", "screencap -p " + tmpFile.getAbsolutePath()}
        );
        boolean done = p.waitFor(15, TimeUnit.SECONDS);
        if (!done) {
            p.destroyForcibly();
            return CommandResult.error("screencap timeout");
        }
        if (p.exitValue() != 0) {
            return CommandResult.error("screencap failed: exit " + p.exitValue());
        }

        // Read file and base64 encode
        if (!tmpFile.exists()) {
            return CommandResult.error("screencap file not found");
        }

        byte[] data = new byte[(int) tmpFile.length()];
        try (InputStream is = new java.io.FileInputStream(tmpFile)) {
            int read = 0;
            while (read < data.length) {
                int n = is.read(data, read, data.length - read);
                if (n < 0) break;
                read += n;
            }
        }
        tmpFile.delete();

        String b64 = Base64.encodeToString(data, Base64.NO_WRAP);
        return new CommandResult(0, b64, "", System.currentTimeMillis() - startMs);
    }
}
