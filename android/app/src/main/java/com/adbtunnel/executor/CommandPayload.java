package com.adbtunnel.executor;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

public class CommandPayload {
    private static final Gson GSON = new Gson();

    public String cmd;
    public String args;
    public int timeout = 30;
    // input fields
    public String action;
    public InputExecutor.InputParams params;
    // install fields
    public String filePath;
    public String options;

    public static CommandPayload from(String json) {
        try {
            JsonObject obj = JsonParser.parseString(json).getAsJsonObject();
            CommandPayload p = new CommandPayload();
            p.cmd = getString(obj, "cmd");
            p.args = getString(obj, "args");
            p.timeout = obj.has("timeout") ? obj.get("timeout").getAsInt() : 30;
            p.action = getString(obj, "action");
            p.filePath = getString(obj, "file_path");
            p.options = getString(obj, "options");
            // Parse input params from the same object
            if ("input".equals(p.cmd)) {
                p.params = GSON.fromJson(obj, InputExecutor.InputParams.class);
            }
            return p;
        } catch (Exception e) {
            CommandPayload p = new CommandPayload();
            p.cmd = "shell";
            p.args = "";
            return p;
        }
    }

    private static String getString(JsonObject obj, String key) {
        return obj.has(key) && !obj.get(key).isJsonNull() ? obj.get(key).getAsString() : null;
    }
}
