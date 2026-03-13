package com.adbtunnel.service;

import android.app.Service;
import android.content.Intent;
import android.os.Build;
import android.os.IBinder;

import androidx.annotation.Nullable;

import com.adbtunnel.device.DeviceInfo;
import com.adbtunnel.device.DeviceRegistry;
import com.adbtunnel.executor.CommandExecutor;
import com.adbtunnel.tunnel.FrameParser;
import com.adbtunnel.tunnel.WsClient;
import com.adbtunnel.tunnel.WsFrame;
import com.adbtunnel.tunnel.WsFrameHandler;
import com.adbtunnel.util.NotificationHelper;
import com.adbtunnel.util.PrefsHelper;

public class TunnelForegroundService extends Service {

    public static final String ACTION_STOP = "com.adbtunnel.STOP";
    public static final String EXTRA_STATUS = "status";

    private WsClient wsClient;
    private CommandExecutor commandExecutor;
    private DeviceInfo deviceInfo;

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && ACTION_STOP.equals(intent.getAction())) {
            stopSelf();
            return START_NOT_STICKY;
        }

        startForeground(NotificationHelper.NOTIF_ID,
            NotificationHelper.build(this, "连接中…"));

        deviceInfo = DeviceRegistry.collectDeviceInfo(this);
        commandExecutor = new CommandExecutor(this);

        String serverUrl = PrefsHelper.getServerUrl(this);

        wsClient = new WsClient(serverUrl, new WsFrameHandler() {
            @Override
            public void onConnected() {
                sendRegisterFrame();
                updateStatus("已连接 · " + deviceInfo.deviceId.substring(0, 8) + "…");
                broadcast("connected");
            }

            @Override
            public void onFrame(byte[] data) {
                try {
                    WsFrame frame = FrameParser.parse(data);
                    handleFrame(frame);
                } catch (Exception e) {
                    // malformed frame, ignore
                }
            }

            @Override
            public void onDisconnected(String reason) {
                updateStatus("重连中…");
                broadcast("disconnected");
            }
        });

        wsClient.connect();
        return START_STICKY;
    }

    private void handleFrame(WsFrame frame) {
        switch (frame.type) {
            case FrameParser.TYPE_REGISTER_ACK:
                updateStatus("已注册 · " + deviceInfo.deviceId.substring(0, 8) + "…");
                break;
            case FrameParser.TYPE_COMMAND:
                commandExecutor.execute(frame, wsClient);
                break;
            case FrameParser.TYPE_PONG:
                break;
            case FrameParser.TYPE_ERROR:
                // server sent error, log but continue
                break;
        }
    }

    private void sendRegisterFrame() {
        wsClient.sendFrame(
            FrameParser.TYPE_REGISTER,
            FrameParser.zeroSessionId(),
            deviceInfo.toJsonString()
        );
    }

    private void updateStatus(String status) {
        startForeground(NotificationHelper.NOTIF_ID,
            NotificationHelper.build(this, status));
    }

    private void broadcast(String event) {
        Intent i = new Intent("com.adbtunnel.TUNNEL_EVENT");
        i.putExtra(EXTRA_STATUS, event);
        sendBroadcast(i);
    }

    @Override
    public void onDestroy() {
        if (wsClient != null) wsClient.close();
        if (commandExecutor != null) commandExecutor.shutdown();
        super.onDestroy();
    }

    @Nullable
    @Override
    public IBinder onBind(Intent intent) { return null; }
}
