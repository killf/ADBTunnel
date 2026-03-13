package com.adbtunnel.util;

import android.content.Context;
import android.content.SharedPreferences;

public class PrefsHelper {
    private static final String PREFS = "adbtunnel_config";
    private static final String KEY_SERVER_URL = "server_url";
    private static final String DEFAULT_URL = "ws://192.168.1.1:8080";

    public static String getServerUrl(Context ctx) {
        return getPrefs(ctx).getString(KEY_SERVER_URL, DEFAULT_URL);
    }

    public static void setServerUrl(Context ctx, String url) {
        getPrefs(ctx).edit().putString(KEY_SERVER_URL, url).apply();
    }

    public static boolean isConfigured(Context ctx) {
        return !getServerUrl(ctx).equals(DEFAULT_URL);
    }

    private static SharedPreferences getPrefs(Context ctx) {
        return ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }
}
