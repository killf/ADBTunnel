# ADBTunnel — 系统架构

## 一、总体架构图

```
                        ┌─────────────────────────────────┐
                        │           调用方 (Caller)         │
                        │  HTTP Client / CI / 测试框架      │
                        └──────────────┬──────────────────┘
                                       │
                          HTTPS  POST /api/devices/{id}/shell
                                       │
          ┌────────────────────────────▼────────────────────────────────┐
          │                   ADBTunnel Server (Rust)                    │
          │                                                              │
          │  ┌──────────────────────────────────────────────────────┐   │
          │  │                    HTTP Layer (Axum)                  │   │
          │  │  POST /shell  GET /screenshot  POST /install  ...     │   │
          │  └──────────────────────────┬───────────────────────────┘   │
          │                             │                                │
          │  ┌──────────────────────────▼───────────────────────────┐   │
          │  │              命令调度器 (Command Dispatcher)           │   │
          │  │   查找 device_id → 获取 WS Session → 发送帧 → 等待响应 │   │
          │  └──────────────────────────┬───────────────────────────┘   │
          │                             │                                │
          │  ┌──────────────────────────▼───────────────────────────┐   │
          │  │          WebSocket 会话管理器 (Session Manager)        │   │
          │  │   HashMap<DeviceId, WsSession>  多路复用帧收发         │   │
          │  └──────────────────────────┬───────────────────────────┘   │
          └───────────────────────────── ┼ ───────────────────────────── ┘
                                         │
                           WSS (TLS 1.3)  │  持久长连接
                                         │
          ┌──────────────────────────────▼────────────────────────────┐
          │                 ADBTunnel Android App (Java)               │
          │                                                            │
          │  ┌─────────────────────────────────────────────────────┐  │
          │  │            TunnelForegroundService                   │  │
          │  │  ┌──────────────┐    ┌──────────────────────────┐   │  │
          │  │  │ WS Client    │    │  CommandExecutor          │   │  │
          │  │  │ (OkHttp)     │───►│  ProcessBuilder / adbd   │   │  │
          │  │  │              │◄───│  FileTransferHelper       │   │  │
          │  │  └──────────────┘    └──────────────────────────┘   │  │
          │  └─────────────────────────────────────────────────────┘  │
          │                                                            │
          │  ┌─────────────────────────────────────────────────────┐  │
          │  │            SetupActivity (引导页)                     │  │
          │  │  ADB 状态检测 → 开发者模式引导 → 启动 Service          │  │
          │  └─────────────────────────────────────────────────────┘  │
          └────────────────────────────────────────────────────────────┘
                              Android 设备 (adbd 运行中)
```

---

## 二、模块说明

### 2.1 Server 模块

| 模块 | 职责 |
|------|------|
| `http_api` | 接收 HTTP 请求，参数校验，返回 JSON 响应 |
| `ws_server` | 接受设备 WebSocket 连接，维护生命周期 |
| `session_manager` | 管理 `DeviceId → WsSession` 映射，线程安全 |
| `dispatcher` | 将 HTTP 命令转为 WS 帧发出，等待响应（含超时） |
| `auth` | Token 验证中间件 |
| `metrics` | Prometheus 指标采集 |
| `config` | 从文件 / 环境变量读取配置 |

### 2.2 Android App 模块

| 模块 | 职责 |
|------|------|
| `SetupActivity` | 首次启动引导：检测 ADB 状态、引导开发者模式 |
| `MainActivity` | 展示连接状态、设备 ID、日志 |
| `TunnelForegroundService` | 前台 Service，持有 WS 连接，接收并分发命令 |
| `WsClient` | 基于 OkHttp 的 WebSocket 封装，含重连逻辑 |
| `CommandExecutor` | 将 WS 帧解析为具体 ADB 命令并执行 |
| `DeviceRegistry` | 生成/存储设备 ID，上报设备信息 |
| `AdbStateChecker` | 检测 USB 调试 / 无线调试是否开启 |

---

## 三、数据流详解

### 3.1 设备注册流（App 启动时）

```
App                         Server
 │                              │
 │──── WS Connect ─────────────►│  (携带 Token)
 │                              │
 │──── REGISTER 帧 ────────────►│  {device_id, model, android_ver, ...}
 │                              │
 │◄─── REGISTER_ACK ───────────│  {status: "ok", server_time: ...}
 │                              │
 │         (心跳循环)            │
 │──── PING ───────────────────►│
 │◄─── PONG ───────────────────│
```

### 3.2 命令执行流（HTTP 调用 → 命令执行 → 返回结果）

```
Caller          Server                          App (Device)
  │                │                                 │
  │─ HTTP POST ───►│                                 │
  │  /shell        │                                 │
  │                │── 生成 session_id               │
  │                │── 查找 device WS session        │
  │                │                                 │
  │                │──── COMMAND 帧 ────────────────►│
  │                │   {session_id, type:"shell",    │
  │                │    payload:"ls /sdcard"}        │
  │                │                                 │
  │                │                        执行命令  │
  │                │                   ProcessBuilder │
  │                │                                 │
  │                │◄─── RESPONSE 帧 ────────────────│
  │                │   {session_id, exit_code:0,     │
  │                │    stdout:"...", stderr:""}     │
  │                │                                 │
  │◄─ HTTP 200 ───│                                 │
  │  {output:...}  │                                 │
```

### 3.3 流式命令（logcat / screenrecord）

```
Caller          Server                          App (Device)
  │                │                                 │
  │─ HTTP POST ───►│  (Accept: text/event-stream)    │
  │  /logcat       │                                 │
  │                │──── STREAM_START 帧 ───────────►│
  │                │                                 │
  │                │◄─── STREAM_DATA 帧 (循环) ──────│
  │◄─ SSE chunk ──│   {session_id, chunk: "..."}    │
  │◄─ SSE chunk ──│                                 │
  │                │                                 │
  │─ HTTP DELETE ─►│  (客户端取消)                   │
  │  /logcat/{sid} │                                 │
  │                │──── STREAM_STOP 帧 ────────────►│
  │                │                                 │
```

### 3.4 文件传输流（push）

```
Caller          Server                          App (Device)
  │                │                                 │
  │─ HTTP POST ───►│  multipart/form-data            │
  │  /push         │  file=<binary>, dest=/sdcard/x  │
  │                │                                 │
  │                │── 缓存文件到临时目录              │
  │                │                                 │
  │                │──── FILE_PUSH 帧 ──────────────►│
  │                │   {session_id, dest, size,      │
  │                │    data: <base64 chunks>}       │
  │                │                                 │
  │                │◄─── FILE_PUSH_ACK ──────────────│
  │◄─ HTTP 200 ───│                                 │
```

---

## 四、关键设计决策

### 4.1 多路复用（Multiplexing）

单个 WebSocket 连接上同时承载多条命令，通过 `session_id`（UUID v4）区分，避免为每个命令新建连接：

```
WS Connection (1 条)
├── session_id: "abc" → shell ls
├── session_id: "def" → screencap
└── session_id: "ghi" → logcat (流式)
```

### 4.2 断线重连策略（Exponential Backoff）

Android App 断线后按指数退避重连，避免服务端雪崩：

```
重试间隔: 1s → 2s → 4s → 8s → 16s → 32s (上限 60s)
重连时携带相同 device_id，服务端更新会话映射
```

### 4.3 命令超时机制

Server 端每个 dispatcher 请求设置默认超时（可配置，默认 30s）；流式命令不超时，但需要客户端主动关闭。

### 4.4 设备 ID 生成

Android App 首次启动时生成 UUID v4 作为 `device_id`，持久化到 SharedPreferences；重装 App 会生成新 ID（此为设计决策，便于多实例共存）。

---

## 五、部署拓扑

### 单机部署
```
Internet ──── Nginx (TLS termination) ──── ADBTunnel Server :8080
                                                    │
                                     WebSocket /ws/device
                                                    │
                                             Android Devices
```

### 高可用部署（未来路线图）
```
Internet ──── L4 LB ──┬── Server Node 1
                       └── Server Node 2
                              │
                         Redis (会话共享)
                              │
                         Android Devices
```
