package com.adbtunnel.device;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Build;

import com.adbtunnel.BuildConfig;

import java.util.UUID;

public class DeviceRegistry {
    private static final String PREFS = "adbtunnel";
    private static final String KEY_DEVICE_ID = "device_id";

    public static String getOrCreateDeviceId(Context ctx) {
        SharedPreferences prefs = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        String id = prefs.getString(KEY_DEVICE_ID, null);
        if (id == null) {
            id = UUID.randomUUID().toString();
            prefs.edit().putString(KEY_DEVICE_ID, id).apply();
        }
        return id;
    }

    public static DeviceInfo collectDeviceInfo(Context ctx) {
        return new DeviceInfo(
            getOrCreateDeviceId(ctx),
            Build.MODEL,
            Build.MANUFACTURER,
            Build.VERSION.RELEASE,
            Build.VERSION.SDK_INT,
            BuildConfig.VERSION_NAME
        );
    }
}
