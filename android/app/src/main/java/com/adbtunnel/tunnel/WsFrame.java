package com.adbtunnel.tunnel;

import java.util.Arrays;

public class WsFrame {
    public final byte type;
    public final byte[] sessionId;
    public final String payload;

    public WsFrame(byte type, byte[] sessionId, String payload) {
        this.type = type;
        this.sessionId = sessionId;
        this.payload = payload;
    }
}
