package com.adbtunnel.tunnel;

public interface WsFrameHandler {
    void onConnected();
    void onFrame(byte[] data);
    void onDisconnected(String reason);
}
