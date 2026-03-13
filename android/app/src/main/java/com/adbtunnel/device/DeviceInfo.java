package com.adbtunnel.device;

import android.content.Context;
import android.os.Build;

public class DeviceInfo {
    public final String deviceId;
    public final String model;
    public final String manufacturer;
    public final String androidVer;
    public final int sdkInt;
    public final String appVer;

    public DeviceInfo(String deviceId, String model, String manufacturer,
                      String androidVer, int sdkInt, String appVer) {
        this.deviceId = deviceId;
        this.model = model;
        this.manufacturer = manufacturer;
        this.androidVer = androidVer;
        this.sdkInt = sdkInt;
        this.appVer = appVer;
    }

    public String toJsonString() {
        return "{" +
            "\"device_id\":\"" + deviceId + "\"," +
            "\"model\":\"" + escape(model) + "\"," +
            "\"manufacturer\":\"" + escape(manufacturer) + "\"," +
            "\"android_ver\":\"" + androidVer + "\"," +
            "\"sdk_int\":" + sdkInt + "," +
            "\"app_ver\":\"" + appVer + "\"" +
            "}";
    }

    private static String escape(String s) {
        if (s == null) return "";
        return s.replace("\\", "\\\\").replace("\"", "\\\"");
    }
}
