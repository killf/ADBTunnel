package com.adbtunnel.tunnel;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;

public class FrameParser {
    public static final byte TYPE_REGISTER     = 0x01;
    public static final byte TYPE_REGISTER_ACK = 0x02;
    public static final byte TYPE_COMMAND      = 0x03;
    public static final byte TYPE_RESPONSE     = 0x04;
    public static final byte TYPE_STREAM_START = 0x05;
    public static final byte TYPE_STREAM_DATA  = 0x06;
    public static final byte TYPE_STREAM_STOP  = 0x07;
    public static final byte TYPE_FILE_PUSH    = 0x08;
    public static final byte TYPE_FILE_ACK     = 0x09;
    public static final byte TYPE_PING         = 0x0C;
    public static final byte TYPE_PONG         = 0x0D;
    public static final byte TYPE_ERROR        = 0x0E;

    public static final int HEADER_SIZE = 17;

    public static WsFrame parse(byte[] data) {
        if (data == null || data.length < HEADER_SIZE) {
            throw new IllegalArgumentException("frame too short: " + (data == null ? 0 : data.length));
        }
        byte type = data[0];
        byte[] sessionId = Arrays.copyOfRange(data, 1, 17);
        String payload = new String(data, 17, data.length - 17, StandardCharsets.UTF_8);
        return new WsFrame(type, sessionId, payload);
    }

    public static byte[] build(byte type, byte[] sessionId, String payloadJson) {
        byte[] payload = payloadJson.getBytes(StandardCharsets.UTF_8);
        byte[] frame = new byte[HEADER_SIZE + payload.length];
        frame[0] = type;
        System.arraycopy(sessionId, 0, frame, 1, 16);
        System.arraycopy(payload, 0, frame, HEADER_SIZE, payload.length);
        return frame;
    }

    public static byte[] zeroSessionId() {
        return new byte[16];
    }
}
