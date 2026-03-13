package com.adbtunnel.executor;

import android.content.Context;

import com.adbtunnel.tunnel.FrameParser;
import com.adbtunnel.tunnel.WsClient;
import com.adbtunnel.tunnel.WsFrame;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class CommandExecutor {

    private final Context context;
    private final ExecutorService threadPool = Executors.newCachedThreadPool();

    public CommandExecutor(Context context) {
        this.context = context;
    }

    public void execute(WsFrame frame, WsClient wsClient) {
        threadPool.submit(() -> {
            CommandResult result;
            try {
                CommandPayload cmd = CommandPayload.from(frame.payload);
                result = dispatch(cmd);
            } catch (Exception e) {
                String msg = e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
                sendError(wsClient, frame.sessionId, "EXEC_ERROR", msg);
                return;
            }
            wsClient.sendFrame(FrameParser.TYPE_RESPONSE, frame.sessionId, result.toJson());
        });
    }

    private CommandResult dispatch(CommandPayload cmd) throws Exception {
        if (cmd.cmd == null) return CommandResult.error("null cmd");

        switch (cmd.cmd) {
            case "shell":
                return new ShellExecutor().execute(cmd.args, cmd.timeout);

            case "input":
                return new InputExecutor(context).execute(cmd.action, cmd.params);

            case "screencap":
                return new ScreencapExecutor(context).execute();

            default:
                return CommandResult.error("unknown_command: " + cmd.cmd);
        }
    }

    private void sendError(WsClient wsClient, byte[] sessionId, String code, String message) {
        String json = "{\"code\":\"" + code + "\",\"message\":\""
            + message.replace("\"", "\\\"") + "\"}";
        wsClient.sendFrame(FrameParser.TYPE_ERROR, sessionId, json);
    }

    public void shutdown() {
        threadPool.shutdownNow();
    }
}
