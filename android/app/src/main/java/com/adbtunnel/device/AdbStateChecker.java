package com.adbtunnel.device;

import android.content.Context;
import android.provider.Settings;

public class AdbStateChecker {

    public static boolean isDeveloperModeEnabled(Context ctx) {
        return Settings.Global.getInt(
            ctx.getContentResolver(),
            Settings.Global.DEVELOPMENT_SETTINGS_ENABLED, 0
        ) != 0;
    }

    public static boolean isAdbEnabled(Context ctx) {
        return Settings.Global.getInt(
            ctx.getContentResolver(),
            Settings.Global.ADB_ENABLED, 0
        ) != 0;
    }

    public static AdbStatus check(Context ctx) {
        boolean devMode = isDeveloperModeEnabled(ctx);
        boolean adb = isAdbEnabled(ctx);
        return new AdbStatus(devMode, adb);
    }

    public static class AdbStatus {
        public final boolean developerModeEnabled;
        public final boolean adbEnabled;
        public final boolean ready;

        AdbStatus(boolean devMode, boolean adb) {
            this.developerModeEnabled = devMode;
            this.adbEnabled = adb;
            this.ready = devMode && adb;
        }
    }
}
