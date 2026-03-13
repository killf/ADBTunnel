# ADBTunnel — 执行流程

## 一、Android App 完整启动流程

```
用户安装 App
      │
      ▼
┌─────────────────────────────────────────────────────────┐
│                    SetupActivity 启动                     │
│                                                          │
│  ① 调用 AdbStateChecker.check()                          │
│     ├─ 读取 Settings.Global.ADB_ENABLED                  │
│     ├─ 读取 Settings.Global.DEVELOPMENT_SETTINGS_ENABLED │
│     └─ 返回状态: {adbEnabled, devModeEnabled}            │
└─────────────────────────┬───────────────────────────────┘
                           │
             ┌─────────────┴─────────────┐
             │ devMode 未开启？           │ devMode 已开启
             ▼                           ▼
┌────────────────────┐       ┌────────────────────────────┐
│  引导开发者模式页   │       │  检查 ADB 是否开启          │
│                    │       │                            │
│  显示步骤：         │       │  ├─ adbEnabled = true?     │
│  1. 设置→关于手机   │       │  │   └─ 进入主流程         │
│  2. 连续点击7次版本号│       │  └─ adbEnabled = false?   │
│  3. 返回→开发者选项  │       │      └─ 引导开启 USB 调试  │
│                    │       └────────────────────────────┘
│  [跳转系统设置按钮] │
└────────────────────┘
             │
             ▼ (用户返回 App 后重新检测)
┌─────────────────────────────────────────────────────────┐
│                    ADB 调试已开启                         │
│                                                          │
│  ② 读取 / 生成 device_id (SharedPreferences)             │
│  ③ 收集设备信息：                                         │
│     - Build.MODEL, Build.MANUFACTURER                    │
│     - Build.VERSION.RELEASE (Android 版本)               │
│     - Build.SERIAL (需要权限，降级用 device_id)           │
│                                                          │
│  ④ 读取 Server 地址和 Token（首次启动弹窗配置）            │
└─────────────────────────┬───────────────────────────────┘
                           │
                           ▼
┌─────────────────────────────────────────────────────────┐
│           启动 TunnelForegroundService                    │
│                                                          │
│  startForegroundService(TunnelForegroundService.class)   │
│  展示持久通知：                                           │
│    "ADBTunnel 运行中 · 已连接 · 设备 ID: xxxx-xxxx"      │
└─────────────────────────┬───────────────────────────────┘
                           │
                           ▼
                   [Service 启动流程 → 见第二节]
```

---

## 二、TunnelForegroundService 启动与连接流程

```
TunnelForegroundService.onStartCommand()
      │
      ▼
┌─────────────────────────────────────────────────────────┐
│  1. 创建前台通知 (NotificationChannel + Notification)    │
│  2. 调用 startForeground(NOTIFICATION_ID, notification) │
│  3. 初始化 WsClient(serverUrl, token, deviceInfo)       │
└─────────────────────────┬───────────────────────────────┘
                           │
                           ▼
┌─────────────────────────────────────────────────────────┐
│                   WsClient.connect()                     │
│                                                          │
│  OkHttpClient.newWebSocket(request, listener)            │
│  URL: wss://server.example.com/ws/device                 │
│  Header: Authorization: Bearer <token>                   │
└─────────────────────────┬───────────────────────────────┘
                           │
           ┌───────────────┴───────────────┐
           │ 连接失败                       │ 连接成功
           ▼                               ▼
┌─────────────────┐           ┌────────────────────────────┐
│  指数退避重连    │           │  onOpen() 回调触发          │
│  计算下次重试间隔 │           │                            │
│  handler.post() │           │  发送 REGISTER 帧：         │
└─────────────────┘           │  {                         │
                               │    "type": "REGISTER",    │
                               │    "device_id": "...",    │
                               │    "model": "Pixel 7",    │
                               │    "android_ver": "13",   │
                               │    "token": "..."         │
                               │  }                        │
                               └──────────────┬────────────┘
                                              │
                                              ▼
                               ┌────────────────────────────┐
                               │  等待 REGISTER_ACK         │
                               │                            │
                               │  成功 → 更新通知状态        │
                               │         启动心跳定时器      │
                               │         重置重连计数器      │
                               │                            │
                               │  失败 → 断开 → 重连         │
                               └──────────────┬────────────┘
                                              │
                                              ▼
                               ┌────────────────────────────┐
                               │        心跳循环             │
                               │  每 30s 发送 PING 帧        │
                               │  等待 PONG (10s 超时)       │
                               │  超时 → 主动断开 → 重连     │
                               └────────────────────────────┘
```

---

## 三、命令执行完整流程

### 3.1 Shell 命令执行

```
步骤 1: Caller 发起 HTTP 请求
────────────────────────────────────────────────────────
POST https://server.example.com/api/devices/device-xxxx/shell
Authorization: Bearer <api_token>
Content-Type: application/json

{
  "command": "ls -la /sdcard/",
  "timeout": 30
}

步骤 2: Server HTTP 层处理
────────────────────────────────────────────────────────
axum handler:
  ① 验证 Authorization Token
  ② 解析 device_id (路径参数)
  ③ 查找 SessionManager.get(device_id)
     └─ 未找到 → 返回 404 {"error": "device_not_found"}
  ④ 生成 session_id = UUID::new_v4()
  ⑤ 创建 ResponseChannel: oneshot::channel()
  ⑥ 注册到 SessionManager.pending_map[session_id] = tx
  ⑦ 调用 Dispatcher.send(device_id, frame)

步骤 3: Server 发送 COMMAND 帧到 App
────────────────────────────────────────────────────────
WS Binary Frame (小端序):
┌──────────┬────────────┬─────────────────────────────┐
│ type(1B) │ sid(16B)   │ payload (JSON UTF-8)        │
│  0x01    │ <UUID>     │ {"cmd":"shell",             │
│          │            │  "args":"ls -la /sdcard/",  │
│          │            │  "timeout":30}              │
└──────────┴────────────┴─────────────────────────────┘

步骤 4: Android App 接收并执行
────────────────────────────────────────────────────────
WsClient.onMessage():
  ① 解析帧头，提取 session_id 和 payload
  ② 提交到 CommandExecutor 线程池（避免阻塞 WS 线程）

CommandExecutor.execute("shell", "ls -la /sdcard/"):
  ① 构建命令: String[] cmd = {"sh", "-c", "ls -la /sdcard/"}
  ② Process p = Runtime.getRuntime().exec(cmd)
  ③ 读取 stdout + stderr（异步读取，避免缓冲区死锁）
  ④ 等待进程结束，获取 exit_code
  ⑤ 构建 RESPONSE 帧

步骤 5: Android App 返回结果
────────────────────────────────────────────────────────
WS Binary Frame:
┌──────────┬────────────┬──────────────────────────────┐
│ type(1B) │ sid(16B)   │ payload (JSON UTF-8)         │
│  0x02    │ <UUID>     │ {"exit_code":0,              │
│          │            │  "stdout":"total 0\n...",    │
│          │            │  "stderr":"",                │
│          │            │  "elapsed_ms":123}           │
└──────────┴────────────┴──────────────────────────────┘

步骤 6: Server 返回 HTTP 响应
────────────────────────────────────────────────────────
Dispatcher 收到响应:
  ① 从 payload 反序列化结果
  ② 通过 oneshot channel 发送给 HTTP handler
  ③ HTTP handler 返回 JSON 响应

HTTP 200 OK
{
  "device_id": "device-xxxx",
  "session_id": "abc-def-...",
  "exit_code": 0,
  "stdout": "total 0\ndrwxrwx--x ...",
  "stderr": "",
  "elapsed_ms": 245
}
```

### 3.2 截图命令执行

```
POST /api/devices/{id}/screenshot
      │
      ▼ Server 生成 session_id，发送 SCREENCAP 帧
      │
      ▼ App 执行:
         ① /system/bin/screencap -p /sdcard/.adbtunnel_tmp.png
         ② 读取文件为 byte[]
         ③ Base64 编码
         ④ 构建 FILE_RESPONSE 帧发回 (大文件分块)
      │
      ▼ Server 组装分块
      │
      ▼ HTTP 响应:
        Content-Type: image/png
        Body: <binary PNG data>
```

### 3.3 APK 安装命令执行

```
POST /api/devices/{id}/install
Content-Type: multipart/form-data
file=<apk binary>
      │
      ▼ Server:
         ① 保存 APK 到临时文件
         ② 生成 session_id
         ③ 分块发送 FILE_PUSH 帧（每块 64KB）
      │
      ▼ App:
         ① 接收分块，写入 /sdcard/.adbtunnel/xxx.apk
         ② 全部接收后执行:
            pm install -r /sdcard/.adbtunnel/xxx.apk
         ③ 解析 pm install 输出（Success / Failure）
         ④ 清理临时文件
         ⑤ 发送 RESPONSE 帧
      │
      ▼ Server:
        HTTP 200 / 422
        {"success": true, "message": "Success"}
```

### 3.4 Logcat 流式命令

```
GET /api/devices/{id}/logcat?filter=*:W&format=brief
Accept: text/event-stream
      │
      ▼ Server:
         ① 发送 STREAM_START 帧到 App
         ② 建立 SSE 响应流
         ③ 注册 session 到流式映射表
      │
      ▼ App:
         ① 执行: logcat -v brief *:W
         ② 循环读取输出行
         ③ 每行/每批次发送 STREAM_DATA 帧
      │
      ▼ Server (循环):
         每收到 STREAM_DATA → SSE event: data: <line>\n\n
      │
      ▼ 客户端断开连接时:
         Server 检测 SSE 断开 → 发送 STREAM_STOP 帧
         App 收到 STREAM_STOP → 终止 logcat 进程
```

---

## 四、断线重连流程

```
正常运行
   │
   │ 网络中断 / 服务器重启
   ▼
onFailure(throwable) / onClosed(code, reason)
   │
   ▼
WsClient.scheduleReconnect()
   │
   ▼
计算退避时间:
  attempt 1: 1000ms
  attempt 2: 2000ms
  attempt 3: 4000ms
  ...
  attempt N: min(2^N * 1000, 60000) ms
   │
   ▼
Handler.postDelayed(connectRunnable, delay)
   │
   ▼
重新执行 connect() 流程（见第二节）
   │
   ▼
连接成功 → 重置 attempt = 0
           重新发送 REGISTER 帧
           Server 更新设备 session 映射
```

> **注意**：重连期间 Server 侧对该设备的 HTTP 请求返回 `503 {"error": "device_offline"}`，客户端收到后可选择重试或放弃。

---

## 五、App 生命周期管理

```
场景一：用户划掉 App（onTaskRemoved）
  └─ Service 继续运行（Foreground Service 不受影响）

场景二：系统低内存回收
  └─ 返回 START_STICKY → 系统会重启 Service，重新 connect()

场景三：设备重启
  └─ BootCompletedReceiver 接收 BOOT_COMPLETED
     └─ startForegroundService(TunnelForegroundService)

场景四：用户主动停止
  └─ stopService() → onDestroy()
     └─ ws.close(1000, "user_stopped")
     └─ 清理心跳定时器
```
