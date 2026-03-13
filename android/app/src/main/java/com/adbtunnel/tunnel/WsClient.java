package com.adbtunnel.tunnel;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;

import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.WebSocket;
import okhttp3.WebSocketListener;
import okio.ByteString;

public class WsClient {
    private final OkHttpClient httpClient;
    private final String serverUrl;
    private final WsFrameHandler frameHandler;
    private final ReconnectScheduler reconnectScheduler;
    private WebSocket webSocket;
    private volatile boolean userStopped = false;

    public WsClient(String serverUrl, WsFrameHandler frameHandler) {
        this.serverUrl = serverUrl;
        this.frameHandler = frameHandler;
        this.httpClient = new OkHttpClient.Builder()
            .readTimeout(0, TimeUnit.MILLISECONDS)
            .build();
        this.reconnectScheduler = new ReconnectScheduler(this::connect);
    }

    public void connect() {
        if (userStopped) return;
        String wsUrl = serverUrl;
        if (!wsUrl.endsWith("/ws/device")) {
            wsUrl = wsUrl.replaceAll("/$", "") + "/ws/device";
        }
        Request request = new Request.Builder()
            .url(wsUrl)
            .build();
        webSocket = httpClient.newWebSocket(request, new InternalListener());
    }

    public boolean sendFrame(byte frameType, byte[] sessionId, String payloadJson) {
        WebSocket ws = webSocket;
        if (ws == null) return false;
        byte[] payload = payloadJson.getBytes(StandardCharsets.UTF_8);
        ByteBuffer buf = ByteBuffer.allocate(1 + 16 + payload.length);
        buf.put(frameType);
        buf.put(sessionId);
        buf.put(payload);
        return ws.send(ByteString.of(buf.array()));
    }

    public void close() {
        userStopped = true;
        reconnectScheduler.cancel();
        WebSocket ws = webSocket;
        if (ws != null) ws.close(1000, "user_stopped");
    }

    private class InternalListener extends WebSocketListener {
        @Override
        public void onOpen(WebSocket ws, Response resp) {
            reconnectScheduler.reset();
            frameHandler.onConnected();
        }

        @Override
        public void onMessage(WebSocket ws, ByteString bytes) {
            frameHandler.onFrame(bytes.toByteArray());
        }

        @Override
        public void onFailure(WebSocket ws, Throwable t, Response resp) {
            String msg = t.getMessage();
            frameHandler.onDisconnected(msg != null ? msg : "connection failed");
            if (!userStopped) reconnectScheduler.schedule();
        }

        @Override
        public void onClosed(WebSocket ws, int code, String reason) {
            frameHandler.onDisconnected(reason);
            if (!userStopped && code != 1000) reconnectScheduler.schedule();
        }
    }
}
