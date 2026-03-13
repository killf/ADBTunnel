# CLAUDE.md — ADBTunnel 项目上下文

## 项目简介

ADBTunnel 将 Android 设备的 ADB 能力通过 WebSocket 隧道暴露到远程服务器，服务器再以 HTTP API 对外提供服务。目标是"任意网络环境下，用 HTTP 操控任意已注册的 Android 设备"。

---

## 代码仓库结构

```
ADBTunnel/
├── android/          # Android App（Java，API 24+）
├── server/           # 服务端（Rust）
├── docs/             # 产品文档（先于代码存在，是实现的依据）
│   ├── 01-product-overview.md    # 项目背景、价值、功能范围
│   ├── 02-system-architecture.md # 架构图、模块说明、数据流
│   ├── 03-execution-flow.md      # 完整执行流程（启动/命令/重连）
│   ├── 04-protocol-design.md     # WS 帧格式 + HTTP API 定义
│   ├── 05-android-design.md      # Java 实现方案与关键类
│   ├── 06-server-design.md       # Rust 实现方案与核心结构
│   ├── 07-security-design.md     # 认证、TLS、限速
│   ├── 08-deployment-guide.md    # 编译、Nginx、Docker、Systemd
│   └── 09-roadmap.md             # 里程碑（M1~M4）与风险
├── CLAUDE.md         # 本文件
└── README.md
```

---

## 技术栈

| 组件 | 技术 |
|------|------|
| Android App | Java，minSdk 24（Android 7.0） |
| 服务端 | Rust，Tokio + Axum + tokio-tungstenite |
| WS 客户端 | OkHttp 4.x |
| JSON | Gson（Android）/ serde_json（Server） |
| TLS 终止 | Nginx（推荐），不在 Server 内部处理 |

---

## 核心架构（一句话）

**Caller → HTTPS → Server → WSS → Android App → 执行命令 → 原路返回**

Server 是无状态的中转层：维护 `HashMap<DeviceId, WsSession>`，HTTP 请求通过 `oneshot channel` 等待 App 响应，默认超时 30s。

---

## 通信协议要点

### WebSocket 帧格式（二进制）

```
[1 byte: type] [16 bytes: session_id (UUID v4)] [N bytes: JSON payload]
```

帧类型关键值：`0x01` REGISTER / `0x02` REGISTER_ACK / `0x03` COMMAND / `0x04` RESPONSE / `0x0C` PING / `0x0D` PONG

完整定义见 `docs/04-protocol-design.md`。

### HTTP API 根路径

`/api/v1/devices/{device_id}/`

主要端点：`shell` / `screenshot` / `install` / `input` / `input/batch` / `logcat` / `push` / `pull`

### 输入操作（input）支持的 action

`tap` / `double_tap` / `long_press` / `swipe` / `key` / `text` / `clipboard_set` / `clipboard_get` / `copy` / `paste`

中文输入：`text` 的 `method: "auto"` 会自动检测非 ASCII，切换到 ClipboardManager → KEYCODE_PASTE 路径。

---

## Android 端关键决策

- **前台 Service**（`TunnelForegroundService`）：`START_STICKY` + 持久通知，保证后台存活
- **断线重连**：指数退避，1s → 2s → 4s … 上限 60s
- **中文输入**：App 直接调用 `ClipboardManager.setPrimaryClip()`（无需 ADBKeyboard），再触发 PASTE
- **ClipboardManager 必须在主线程调用**，通过 `new Handler(mainLooper).post()` + `CountDownLatch` 同步
- **Token 存储**：`EncryptedSharedPreferences`（Jetpack Security，AES256-GCM）
- **开机自启**：`BootCompletedReceiver` 监听 `BOOT_COMPLETED`

关键 Java 包路径：`com.adbtunnel.{ui,service,tunnel,executor,device,util}`

---

## Server 端关键决策

- **SessionManager**：`Arc<RwLock<HashMap<DeviceId, WsSession>>>`，写锁仅在注册/注销时持有
- **命令调度**：HTTP handler 创建 `oneshot::channel`，注册到 pending map，发帧后 `tokio::time::timeout` 等待
- **流式命令**（logcat）：SSE 响应，App 发 `STREAM_DATA` 帧，Server 转发为 SSE chunk；客户端断开时发 `STREAM_STOP` 帧
- **文件传输**：分块 64KB，Base64 编码写入 WS 帧 payload

关键 Rust 模块：`http/handlers/` / `ws/` / `session_manager.rs` / `dispatcher.rs`

---

## 安全设计（精简版）

- **单 Token**（统一用于设备注册和 API 调用），通过环境变量 `ADBTUNNEL_TOKEN` 注入
- **TLS 强制**（生产）：Nginx 做终止，`wss://` + `https://`
- **命令黑名单**：Server 侧过滤 `rm -rf /` 等高危模式
- **限速**：Tower middleware，每 IP 每秒 20 请求

---

## 开发约定

### 写代码前先看文档

`docs/` 目录是实现的唯一依据。修改协议或 API 时，**先更新对应文档，再改代码**。

### Android

- 最低 API：24（不使用 API 26+ 专属 API 时须加版本判断）
- 所有 ClipboardManager 调用必须在主线程，用 Handler + CountDownLatch 同步
- 执行 shell 命令使用 `Runtime.getRuntime().exec(new String[]{"sh","-c", cmd})`，不拼接单个字符串（防注入）
- 不使用 `AsyncTask`（已废弃），改用线程池 `Executors.newCachedThreadPool()`

### Server（Rust）

- 异步函数一律 `async fn`，不使用 `block_in_place`
- 错误处理：内部用 `anyhow::Result`，HTTP 响应用 `thiserror` 定义的 `AppError` 转 JSON
- 日志：`tracing::info!` / `warn!` / `error!`，不用 `println!`
- 配置：从 `config/` 目录 TOML 文件读取，环境变量可覆盖（`ADBTUNNEL_` 前缀）

### 通用

- 不添加未被需求覆盖的功能
- 不写无用的注释（代码自解释）
- 提交前确认文档与代码一致

---

## 当前进度（M1 阶段，尚未开始编码）

文档已完备，代码目录（`android/`、`server/`）尚未创建。下一步是按 M1 里程碑开始实现：

**M1 目标**（Shell 命令 + 截图可用）：
1. `server/` — Cargo 工程骨架、WebSocket 注册、Shell HTTP API
2. `android/` — Gradle 工程骨架、AdbStateChecker、TunnelForegroundService、ShellExecutor
