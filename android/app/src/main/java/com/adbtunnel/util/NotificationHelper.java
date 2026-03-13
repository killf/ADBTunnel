package com.adbtunnel.util;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.os.Build;

import androidx.core.app.NotificationCompat;

import com.adbtunnel.R;
import com.adbtunnel.ui.MainActivity;

public class NotificationHelper {
    public static final String CHANNEL_ID = "adbtunnel_tunnel";
    public static final int NOTIF_ID = 1001;

    public static void createChannel(Context ctx) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                CHANNEL_ID, "ADBTunnel 隧道", NotificationManager.IMPORTANCE_LOW
            );
            channel.setDescription("保持 ADB 隧道连接");
            channel.setShowBadge(false);
            ctx.getSystemService(NotificationManager.class).createNotificationChannel(channel);
        }
    }

    public static Notification build(Context ctx, String status) {
        createChannel(ctx);
        Intent intent = new Intent(ctx, MainActivity.class);
        int flags = PendingIntent.FLAG_UPDATE_CURRENT;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            flags |= PendingIntent.FLAG_IMMUTABLE;
        }
        PendingIntent pi = PendingIntent.getActivity(ctx, 0, intent, flags);

        return new NotificationCompat.Builder(ctx, CHANNEL_ID)
            .setContentTitle("ADBTunnel")
            .setContentText(status)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentIntent(pi)
            .setOngoing(true)
            .setSilent(true)
            .build();
    }
}
