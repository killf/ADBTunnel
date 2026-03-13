package com.adbtunnel.executor;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.os.Handler;
import android.os.Looper;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

public class InputExecutor {

    private final Context context;

    private static final Map<String, String> KEY_MAP = new HashMap<String, String>() {{
        put("home",         "KEYCODE_HOME");
        put("back",         "KEYCODE_BACK");
        put("menu",         "KEYCODE_MENU");
        put("power",        "KEYCODE_POWER");
        put("volume_up",    "KEYCODE_VOLUME_UP");
        put("volume_down",  "KEYCODE_VOLUME_DOWN");
        put("recent",       "KEYCODE_APP_SWITCH");
        put("enter",        "KEYCODE_ENTER");
        put("delete",       "KEYCODE_DEL");
        put("escape",       "KEYCODE_ESCAPE");
        put("tab",          "KEYCODE_TAB");
        put("copy",         "KEYCODE_COPY");
        put("paste",        "KEYCODE_PASTE");
        put("cut",          "KEYCODE_CUT");
        put("screenshot",   "KEYCODE_SYSRQ");
        put("notification", "KEYCODE_NOTIFICATION");
    }};

    public InputExecutor(Context context) {
        this.context = context;
    }

    public CommandResult execute(String action, InputParams p) throws Exception {
        if (action == null) return CommandResult.error("null action");
        if (p == null) p = new InputParams();
        long startMs = System.currentTimeMillis();

        switch (action) {
            case "tap":
                return runShell(startMs, "input tap " + p.x + " " + p.y);

            case "double_tap":
                int itv = p.interval_ms > 0 ? p.interval_ms : 100;
                runShell(startMs, "input tap " + p.x + " " + p.y);
                Thread.sleep(itv);
                return runShell(startMs, "input tap " + p.x + " " + p.y);

            case "long_press":
                int dur = p.duration_ms > 0 ? p.duration_ms : 1000;
                return runShell(startMs,
                    "input swipe " + p.x + " " + p.y + " " + p.x + " " + p.y + " " + dur);

            case "swipe":
                int sd = p.duration_ms > 0 ? p.duration_ms : 300;
                return runShell(startMs,
                    "input swipe " + p.from_x + " " + p.from_y
                    + " " + p.to_x + " " + p.to_y + " " + sd);

            case "key":
                return runShell(startMs, "input keyevent " + resolveKeycode(p.key));

            case "text":
                return inputText(startMs, p.text, p.method);

            case "clipboard_set":
                return setClipboard(startMs, p.text);

            case "clipboard_get":
                return getClipboard(startMs);

            case "copy":
                runShell(startMs, "input keyevent KEYCODE_COPY");
                Thread.sleep(100);
                return getClipboard(startMs);

            case "paste":
                return runShell(startMs, "input keyevent KEYCODE_PASTE");

            default:
                return CommandResult.error("unknown_input_action: " + action);
        }
    }

    private CommandResult inputText(long startMs, String text, String method) throws Exception {
        if (text == null) text = "";
        boolean needClipboard = "clipboard".equals(method)
            || (!"ascii".equals(method) && !isAsciiOnly(text));

        if (needClipboard) {
            setClipboardDirect(text);
            Thread.sleep(80);
            return runShell(startMs, "input keyevent KEYCODE_PASTE");
        } else {
            return runShell(startMs, "input text " + shellQuote(text));
        }
    }

    private CommandResult setClipboard(long startMs, String text) throws Exception {
        CountDownLatch latch = new CountDownLatch(1);
        new Handler(context.getMainLooper()).post(() -> {
            setClipboardDirect(text);
            latch.countDown();
        });
        latch.await(3, TimeUnit.SECONDS);
        return new CommandResult(0, "", "", System.currentTimeMillis() - startMs);
    }

    private void setClipboardDirect(String text) {
        ClipboardManager cm = (ClipboardManager)
            context.getSystemService(Context.CLIPBOARD_SERVICE);
        cm.setPrimaryClip(ClipData.newPlainText("adbtunnel", text != null ? text : ""));
    }

    private CommandResult getClipboard(long startMs) throws Exception {
        final String[] result = {""};
        CountDownLatch latch = new CountDownLatch(1);
        new Handler(context.getMainLooper()).post(() -> {
            try {
                ClipboardManager cm = (ClipboardManager)
                    context.getSystemService(Context.CLIPBOARD_SERVICE);
                if (cm.hasPrimaryClip() && cm.getPrimaryClip() != null) {
                    ClipData.Item item = cm.getPrimaryClip().getItemAt(0);
                    CharSequence cs = item.coerceToText(context);
                    if (cs != null) result[0] = cs.toString();
                }
            } finally {
                latch.countDown();
            }
        });
        latch.await(3, TimeUnit.SECONDS);
        return new CommandResult(0, result[0], "", System.currentTimeMillis() - startMs);
    }

    private CommandResult runShell(long startMs, String cmd) throws Exception {
        Process p = Runtime.getRuntime().exec(new String[]{"sh", "-c", cmd});
        boolean done = p.waitFor(10, TimeUnit.SECONDS);
        if (!done) {
            p.destroyForcibly();
            throw new TimeoutException("input cmd timeout");
        }
        return new CommandResult(p.exitValue(), "", "", System.currentTimeMillis() - startMs);
    }

    private String resolveKeycode(String key) {
        if (key == null) return "KEYCODE_HOME";
        try { Integer.parseInt(key); return key; } catch (NumberFormatException ignored) {}
        String mapped = KEY_MAP.get(key.toLowerCase());
        return mapped != null ? mapped : key.toUpperCase();
    }

    private static boolean isAsciiOnly(String s) {
        for (int i = 0; i < s.length(); i++) {
            if (s.charAt(i) > 127) return false;
        }
        return true;
    }

    private static String shellQuote(String text) {
        return "'" + text.replace("'", "'\\''") + "'";
    }

    public static class InputParams {
        public int    x, y;
        public int    from_x, from_y, to_x, to_y;
        public int    duration_ms;
        public int    interval_ms;
        public String key;
        public String text;
        public String method;
    }
}
