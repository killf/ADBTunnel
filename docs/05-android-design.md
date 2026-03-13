# ADBTunnel — Android 端设计

## 一、模块结构

```
app/
├── src/main/
│   ├── java/com/adbtunnel/
│   │   ├── ui/
│   │   │   ├── SetupActivity.java        // 引导页
│   │   │   ├── MainActivity.java         // 主页（状态监控）
│   │   │   └── ConfigActivity.java       // 服务器配置页
│   │   ├── service/
│   │   │   └── TunnelForegroundService.java  // 核心前台服务
│   │   ├── tunnel/
│   │   │   ├── WsClient.java             // WebSocket 客户端封装
│   │   │   ├── FrameParser.java          // 帧解析/构建
│   │   │   └── ReconnectScheduler.java   // 断线重连调度
│   │   ├── executor/
│   │   │   ├── CommandExecutor.java      // 命令执行总入口
│   │   │   ├── ShellExecutor.java        // shell 命令执行
│   │   │   ├── InputExecutor.java        // 输入操作执行（点击/滑动/按键）
│   │   │   ├── ScreencapExecutor.java    // 截图执行
│   │   │   ├── InstallExecutor.java      // APK 安装执行
│   │   │   ├── LogcatExecutor.java       // logcat 流式执行
│   │   │   └── FileTransferExecutor.java // 文件推拉执行
│   │   ├── device/
│   │   │   ├── DeviceRegistry.java       // 设备 ID 管理
│   │   │   ├── AdbStateChecker.java      // ADB 状态检测
│   │   │   └── DeviceInfo.java           // 设备信息 POJO
│   │   └── util/
│   │       ├── NotificationHelper.java   // 通知工具
│   │       └── PrefsHelper.java          // SharedPreferences 封装
│   ├── res/
│   └── AndroidManifest.xml
└── build.gradle
```

---

## 二、关键类设计

### 2.1 AdbStateChecker

检测设备 ADB 调试开启状态。

```java
public class AdbStateChecker {

    /**
     * 检查开发者模式是否开启。
     * Android 4.2+ 需要手动开启。
     */
    public static boolean isDeveloperModeEnabled(Context ctx) {
        return Settings.Global.getInt(
            ctx.getContentResolver(),
            Settings.Global.DEVELOPMENT_SETTINGS_ENABLED, 0
        ) != 0;
    }

    /**
     * 检查 USB 调试是否开启。
     */
    public static boolean isAdbEnabled(Context ctx) {
        return Settings.Global.getInt(
            ctx.getContentResolver(),
            Settings.Global.ADB_ENABLED, 0
        ) != 0;
    }

    /**
     * 综合状态
     */
    public static AdbStatus check(Context ctx) {
        boolean devMode = isDeveloperModeEnabled(ctx);
        boolean adb = isAdbEnabled(ctx);
        return new AdbStatus(devMode, adb);
    }

    public static class AdbStatus {
        public final boolean developerModeEnabled;
        public final boolean adbEnabled;
        public final boolean ready; // devMode && adb

        AdbStatus(boolean devMode, boolean adb) {
            this.developerModeEnabled = devMode;
            this.adbEnabled = adb;
            this.ready = devMode && adb;
        }
    }
}
```

### 2.2 DeviceRegistry

负责生成和持久化唯一设备 ID，并收集设备信息。

```java
public class DeviceRegistry {
    private static final String PREF_DEVICE_ID = "device_id";

    public static String getOrCreateDeviceId(Context ctx) {
        SharedPreferences prefs = ctx.getSharedPreferences("adbtunnel", Context.MODE_PRIVATE);
        String id = prefs.getString(PREF_DEVICE_ID, null);
        if (id == null) {
            id = UUID.randomUUID().toString();
            prefs.edit().putString(PREF_DEVICE_ID, id).apply();
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
```

### 2.3 WsClient

封装 OkHttp WebSocket，提供重连和帧发送接口。

```java
public class WsClient {
    private final OkHttpClient httpClient;
    private final String serverUrl;
    private final String token;
    private WebSocket webSocket;
    private WsListener listener;
    private ReconnectScheduler reconnectScheduler;
    private volatile boolean userStopped = false;

    public WsClient(String serverUrl, String token, WsFrameHandler handler) {
        this.serverUrl = serverUrl;
        this.token = token;
        this.httpClient = new OkHttpClient.Builder()
            .readTimeout(0, TimeUnit.MILLISECONDS) // 长连接不超时
            .pingInterval(30, TimeUnit.SECONDS)    // OkHttp 内置心跳
            .build();
        this.reconnectScheduler = new ReconnectScheduler(this::connect);
        this.listener = new WsListener(handler, reconnectScheduler);
    }

    public void connect() {
        if (userStopped) return;
        Request request = new Request.Builder()
            .url(serverUrl + "/ws/device")
            .header("Authorization", "Bearer " + token)
            .build();
        webSocket = httpClient.newWebSocket(request, listener);
    }

    public boolean sendFrame(byte frameType, byte[] sessionId, String payloadJson) {
        if (webSocket == null) return false;
        byte[] payload = payloadJson.getBytes(StandardCharsets.UTF_8);
        ByteBuffer buf = ByteBuffer.allocate(1 + 16 + payload.length);
        buf.put(frameType);
        buf.put(sessionId);
        buf.put(payload);
        return webSocket.send(ByteString.of(buf.array()));
    }

    public void close() {
        userStopped = true;
        reconnectScheduler.cancel();
        if (webSocket != null) webSocket.close(1000, "user_stopped");
    }

    // 内部 WebSocketListener
    private class WsListener extends WebSocketListener {
        private final WsFrameHandler handler;
        private final ReconnectScheduler scheduler;

        WsListener(WsFrameHandler handler, ReconnectScheduler scheduler) {
            this.handler = handler;
            this.scheduler = scheduler;
        }

        @Override
        public void onOpen(WebSocket ws, Response resp) {
            scheduler.reset();
            handler.onConnected();
        }

        @Override
        public void onMessage(WebSocket ws, ByteString bytes) {
            handler.onFrame(bytes.toByteArray());
        }

        @Override
        public void onFailure(WebSocket ws, Throwable t, Response resp) {
            handler.onDisconnected(t.getMessage());
            if (!userStopped) scheduler.schedule();
        }

        @Override
        public void onClosed(WebSocket ws, int code, String reason) {
            handler.onDisconnected(reason);
            if (!userStopped && code != 1000) scheduler.schedule();
        }
    }
}
```

### 2.4 FrameParser

帧的序列化与反序列化。

```java
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

    public static final int HEADER_SIZE = 17; // 1 + 16

    public static WsFrame parse(byte[] data) {
        if (data.length < HEADER_SIZE) throw new IllegalArgumentException("frame too short");
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
```

### 2.5 CommandExecutor

根据帧类型路由到具体执行器。

```java
public class CommandExecutor {
    private final ExecutorService threadPool = Executors.newCachedThreadPool();

    public void execute(WsFrame frame, WsClient wsClient) {
        threadPool.submit(() -> {
            try {
                CommandPayload cmd = parseCommand(frame.payload);
                CommandResult result;

                switch (cmd.cmd) {
                    case "shell":
                        result = new ShellExecutor().execute(cmd.args, cmd.timeout);
                        break;
                    case "input":
                        result = new InputExecutor().execute(cmd.action, cmd.params);
                        break;
                    case "screencap":
                        result = new ScreencapExecutor().execute();
                        break;
                    case "install":
                        result = new InstallExecutor().execute(cmd.filePath, cmd.options);
                        break;
                    default:
                        result = CommandResult.error("unknown_command: " + cmd.cmd);
                }

                wsClient.sendFrame(
                    FrameParser.TYPE_RESPONSE,
                    frame.sessionId,
                    result.toJson()
                );
            } catch (Exception e) {
                wsClient.sendFrame(
                    FrameParser.TYPE_ERROR,
                    frame.sessionId,
                    "{\"code\":\"EXEC_ERROR\",\"message\":\"" + e.getMessage() + "\"}"
                );
            }
        });
    }
}
```

### 2.6 ShellExecutor

执行 Shell 命令，安全读取 stdout/stderr。

```java
public class ShellExecutor {
    public CommandResult execute(String command, int timeoutSeconds) throws Exception {
        long startMs = System.currentTimeMillis();

        Process process = new ProcessBuilder("sh", "-c", command)
            .redirectErrorStream(false)
            .start();

        // 异步读取 stdout 和 stderr（避免缓冲区死锁）
        StringBuilder stdout = new StringBuilder();
        StringBuilder stderr = new StringBuilder();

        Thread stdoutThread = new Thread(() -> {
            try (BufferedReader r = new BufferedReader(
                    new InputStreamReader(process.getInputStream()))) {
                String line;
                while ((line = r.readLine()) != null) stdout.append(line).append('\n');
            } catch (IOException ignored) {}
        });
        Thread stderrThread = new Thread(() -> {
            try (BufferedReader r = new BufferedReader(
                    new InputStreamReader(process.getErrorStream()))) {
                String line;
                while ((line = r.readLine()) != null) stderr.append(line).append('\n');
            } catch (IOException ignored) {}
        });

        stdoutThread.start();
        stderrThread.start();

        boolean finished = process.waitFor(timeoutSeconds, TimeUnit.SECONDS);
        if (!finished) {
            process.destroyForcibly();
            throw new TimeoutException("command timed out after " + timeoutSeconds + "s");
        }

        stdoutThread.join(1000);
        stderrThread.join(1000);

        return new CommandResult(
            process.exitValue(),
            stdout.toString(),
            stderr.toString(),
            System.currentTimeMillis() - startMs
        );
    }
}
```

### 2.7 InputExecutor

执行所有输入操作，底层按场景选择 `input` 命令或 Android API。

```java
public class InputExecutor {

    private final Context context;

    public InputExecutor(Context context) {
        this.context = context;
    }

    // 按键别名 → Android KEYCODE 字符串映射
    private static final Map<String, String> KEY_MAP = new HashMap<String, String>() {{
        put("home",         "KEYCODE_HOME");
        put("back",         "KEYCODE_BACK");
        put("menu",         "KEYCODE_MENU");
        put("power",        "KEYCODE_POWER");
        put("volume_up",    "KEYCODE_VOLUME_UP");
        put("volume_down",  "KEYCODE_VOLUME_DOWN");
        put("recent",       "KEYCODE_APP_SWITCH");
        put("enter",        "KEYCODE_ENTER");
        put("delete",       "KEYCODE_DEL");
        put("escape",       "KEYCODE_ESCAPE");
        put("tab",          "KEYCODE_TAB");
        put("copy",         "KEYCODE_COPY");
        put("paste",        "KEYCODE_PASTE");
        put("cut",          "KEYCODE_CUT");
        put("select_all",   "KEYCODE_A");   // 配合 Ctrl 使用
        put("screenshot",   "KEYCODE_SYSRQ");
        put("notification", "KEYCODE_NOTIFICATION");
    }};

    public CommandResult execute(String action, InputParams p) throws Exception {
        long startMs = System.currentTimeMillis();
        switch (action) {
            case "tap":
                return shell(startMs, "input tap " + p.x + " " + p.y);

            case "double_tap":
                int interval = p.interval_ms > 0 ? p.interval_ms : 100;
                shell(startMs, "input tap " + p.x + " " + p.y);
                Thread.sleep(interval);
                return shell(startMs, "input tap " + p.x + " " + p.y);

            case "long_press":
                int dur = p.duration_ms > 0 ? p.duration_ms : 1000;
                return shell(startMs,
                    "input swipe " + p.x + " " + p.y
                    + " " + p.x + " " + p.y + " " + dur);

            case "swipe":
                int sd = p.duration_ms > 0 ? p.duration_ms : 300;
                return shell(startMs,
                    "input swipe " + p.from_x + " " + p.from_y
                    + " " + p.to_x + " " + p.to_y + " " + sd);

            case "key":
                return shell(startMs, "input keyevent " + resolveKeycode(p.key));

            case "text":
                return inputText(startMs, p.text, p.method);

            case "clipboard_set":
                return setClipboard(startMs, p.text);

            case "clipboard_get":
                return getClipboard(startMs);

            case "copy":
                // 触发 COPY 快捷键，再读剪贴板
                shell(startMs, "input keyevent KEYCODE_COPY");
                Thread.sleep(100); // 等待系统写入剪贴板
                return getClipboard(startMs);

            case "paste":
                return shell(startMs, "input keyevent KEYCODE_PASTE");

            default:
                return CommandResult.error("unknown_input_action: " + action);
        }
    }

    // ── 文本输入：自动选择路径 ──────────────────────────────────────────

    private CommandResult inputText(long startMs, String text, String method) throws Exception {
        if (text == null) text = "";

        // 自动判断：含非 ASCII 字符则走剪贴板路径
        boolean needClipboard = "clipboard".equals(method)
            || ("auto".equals(method) || method == null) && !isAsciiOnly(text);

        if (needClipboard) {
            // 1. 通过 ClipboardManager 写入剪贴板（支持任意 Unicode）
            setClipboardDirect(text);
            Thread.sleep(80);
            // 2. 触发粘贴
            return shell(startMs, "input keyevent KEYCODE_PASTE");
        } else {
            // 纯 ASCII：直接 input text（速度更快，不依赖焦点控件支持粘贴）
            return shell(startMs, "input text " + shellQuote(text));
        }
    }

    private static boolean isAsciiOnly(String s) {
        for (int i = 0; i < s.length(); i++) {
            if (s.charAt(i) > 127) return false;
        }
        return true;
    }

    // ── 剪贴板操作（直接调用 Android API）─────────────────────────────

    /**
     * 写入剪贴板。
     * ClipboardManager 写入无需前台权限，前台 Service 可直接调用。
     * 必须在主线程执行（通过 Handler post 到主线程）。
     */
    private CommandResult setClipboard(long startMs, String text) throws Exception {
        CountDownLatch latch = new CountDownLatch(1);
        new Handler(context.getMainLooper()).post(() -> {
            setClipboardDirect(text);
            latch.countDown();
        });
        latch.await(3, TimeUnit.SECONDS);
        return new CommandResult(0, "", "", System.currentTimeMillis() - startMs);
    }

    private void setClipboardDirect(String text) {
        ClipboardManager cm = (ClipboardManager)
            context.getSystemService(Context.CLIPBOARD_SERVICE);
        cm.setPrimaryClip(ClipData.newPlainText("adbtunnel", text));
    }

    /**
     * 读取剪贴板。
     * Android 10+ 对后台读剪贴板有限制，前台 Service 可读（非 Activity 时可能返回空）。
     * 通过主线程执行绕过部分限制。
     */
    private CommandResult getClipboard(long startMs) throws Exception {
        final String[] result = {""};
        CountDownLatch latch = new CountDownLatch(1);
        new Handler(context.getMainLooper()).post(() -> {
            try {
                ClipboardManager cm = (ClipboardManager)
                    context.getSystemService(Context.CLIPBOARD_SERVICE);
                if (cm.hasPrimaryClip()) {
                    ClipData.Item item = cm.getPrimaryClip().getItemAt(0);
                    CharSequence text = item.coerceToText(context);
                    if (text != null) result[0] = text.toString();
                }
            } finally {
                latch.countDown();
            }
        });
        latch.await(3, TimeUnit.SECONDS);
        return new CommandResult(0, result[0], "", System.currentTimeMillis() - startMs);
    }

    // ── 工具方法 ────────────────────────────────────────────────────────

    private CommandResult shell(long startMs, String cmd) throws Exception {
        Process p = Runtime.getRuntime().exec(new String[]{"sh", "-c", cmd});
        boolean done = p.waitFor(10, TimeUnit.SECONDS);
        if (!done) { p.destroyForcibly(); throw new TimeoutException("input cmd timeout"); }
        return new CommandResult(p.exitValue(), "", "", System.currentTimeMillis() - startMs);
    }

    private String resolveKeycode(String key) {
        if (key == null) return "KEYCODE_HOME";
        try { Integer.parseInt(key); return key; } catch (NumberFormatException ignored) {}
        String mapped = KEY_MAP.get(key.toLowerCase());
        return mapped != null ? mapped : key.toUpperCase();
    }

    /** 用单引号包裹，处理内部单引号，适用于 `input text` 参数 */
    private static String shellQuote(String text) {
        return "'" + text.replace("'", "'\\''") + "'";
    }

    // 输入参数 POJO
    public static class InputParams {
        public int    x, y;
        public int    from_x, from_y, to_x, to_y;
        public int    duration_ms;
        public int    interval_ms;
        public String key;
        public String text;
        public String method;   // auto | ascii | clipboard
    }
}
```

#### 批量操作执行

```java
public class BatchInputExecutor {
    private final InputExecutor inputExecutor;

    public BatchInputExecutor(Context context) {
        this.inputExecutor = new InputExecutor(context);
    }

    public BatchResult executeBatch(List<BatchAction> actions) throws Exception {
        List<BatchStepResult> results = new ArrayList<>();
        long batchStart = System.currentTimeMillis();

        for (int i = 0; i < actions.size(); i++) {
            BatchAction action = actions.get(i);
            long stepStart = System.currentTimeMillis();

            if (action.delay_ms > 0) {
                Thread.sleep(action.delay_ms);
                results.add(new BatchStepResult(i, "delay", true,
                    System.currentTimeMillis() - stepStart));
            } else {
                try {
                    CommandResult r = inputExecutor.execute(action.action, action.params);
                    results.add(new BatchStepResult(i, action.action,
                        r.exitCode == 0, r.elapsedMs));
                } catch (Exception e) {
                    results.add(new BatchStepResult(i, action.action, false, 0));
                    break; // 任一步骤失败中止
                }
            }
        }

        return new BatchResult(results, System.currentTimeMillis() - batchStart);
    }
}
```

### 2.8 TunnelForegroundService（骨架）

```java
public class TunnelForegroundService extends Service {
    private static final int NOTIF_ID = 1001;
    private WsClient wsClient;
    private CommandExecutor commandExecutor;

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        startForeground(NOTIF_ID, buildNotification("连接中..."));

        DeviceInfo info = DeviceRegistry.collectDeviceInfo(this);
        String serverUrl = PrefsHelper.getServerUrl(this);
        String token = PrefsHelper.getToken(this);

        commandExecutor = new CommandExecutor();
        wsClient = new WsClient(serverUrl, token, new WsFrameHandler() {
            @Override
            public void onConnected() {
                sendRegisterFrame(info);
                updateNotification("已连接 · " + info.deviceId.substring(0, 8));
            }

            @Override
            public void onFrame(byte[] data) {
                WsFrame frame = FrameParser.parse(data);
                switch (frame.type) {
                    case FrameParser.TYPE_REGISTER_ACK:
                        // 注册成功，开始等待命令
                        break;
                    case FrameParser.TYPE_COMMAND:
                        commandExecutor.execute(frame, wsClient);
                        break;
                    case FrameParser.TYPE_STREAM_START:
                        commandExecutor.executeStream(frame, wsClient);
                        break;
                    case FrameParser.TYPE_STREAM_STOP:
                        commandExecutor.stopStream(frame.sessionId);
                        break;
                    case FrameParser.TYPE_FILE_PUSH:
                        commandExecutor.receiveFilePush(frame, wsClient);
                        break;
                    case FrameParser.TYPE_PONG:
                        // 心跳响应，忽略
                        break;
                }
            }

            @Override
            public void onDisconnected(String reason) {
                updateNotification("断线中，重连...");
            }
        });

        wsClient.connect();
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        if (wsClient != null) wsClient.close();
        super.onDestroy();
    }

    private void sendRegisterFrame(DeviceInfo info) {
        String payload = info.toJsonString();
        wsClient.sendFrame(FrameParser.TYPE_REGISTER, FrameParser.zeroSessionId(), payload);
    }

    @Nullable
    @Override
    public IBinder onBind(Intent intent) { return null; }
}
```

---

## 三、AndroidManifest.xml 关键配置

```xml
<manifest xmlns:android="http://schemas.android.com/apk/res/android">

    <!-- 网络权限 -->
    <uses-permission android:name="android.permission.INTERNET" />
    <uses-permission android:name="android.permission.ACCESS_NETWORK_STATE" />

    <!-- 前台服务（Android 9+） -->
    <uses-permission android:name="android.permission.FOREGROUND_SERVICE" />

    <!-- 开机自启 -->
    <uses-permission android:name="android.permission.RECEIVE_BOOT_COMPLETED" />

    <!-- 读/写外部存储（APK 安装临时目录） -->
    <uses-permission android:name="android.permission.READ_EXTERNAL_STORAGE" />
    <uses-permission android:name="android.permission.WRITE_EXTERNAL_STORAGE"
        android:maxSdkVersion="28" />

    <!-- Android 11+ 安装未知来源 APK -->
    <uses-permission android:name="android.permission.REQUEST_INSTALL_PACKAGES" />

    <application ...>
        <!-- 前台服务 type（Android 14+ 必须） -->
        <service
            android:name=".service.TunnelForegroundService"
            android:foregroundServiceType="dataSync"
            android:exported="false" />

        <!-- 开机自启广播接收器 -->
        <receiver
            android:name=".receiver.BootCompletedReceiver"
            android:exported="false">
            <intent-filter>
                <action android:name="android.intent.action.BOOT_COMPLETED" />
            </intent-filter>
        </receiver>
    </application>
</manifest>
```

---

## 四、依赖（build.gradle）

```groovy
dependencies {
    // WebSocket 客户端
    implementation 'com.squareup.okhttp3:okhttp:4.12.0'

    // JSON 序列化
    implementation 'com.google.code.gson:gson:2.10.1'

    // 前台服务保活兼容
    implementation 'androidx.core:core:1.12.0'

    // 设置页面
    implementation 'androidx.preference:preference:1.2.1'
}
```

---

## 五、保活策略

| 场景 | 策略 |
|------|------|
| 用户划掉 App | `START_STICKY`，系统自动重启 Service |
| 系统低内存 OOM | `START_STICKY`，重启后重新 connect |
| 设备重启 | `BootCompletedReceiver` 监听 `BOOT_COMPLETED` |
| 厂商后台限制 | 引导用户在"电池优化"中设置 App 为"不限制" |
| 前台通知 | 保持前台服务（Foreground Service）避免被系统回收 |
