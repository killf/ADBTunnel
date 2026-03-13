# ADBTunnel — Server 设计（Rust）

## 一、项目结构

```
server/
├── Cargo.toml
├── config/
│   └── default.toml              // 默认配置
└── src/
    ├── main.rs                   // 入口：初始化 + 启动
    ├── config.rs                 // 配置结构体（从文件/env 读取）
    ├── error.rs                  // 统一错误类型
    ├── http/
    │   ├── mod.rs
    │   ├── router.rs             // Axum 路由定义
    │   ├── handlers/
    │   │   ├── devices.rs        // 设备列表/详情
    │   │   ├── shell.rs          // POST /shell
    │   │   ├── screenshot.rs     // GET /screenshot
    │   │   ├── install.rs        // POST /install
    │   │   ├── logcat.rs         // GET /logcat (SSE)
    │   │   ├── push.rs           // POST /push
    │   │   └── pull.rs           // GET /pull
    │   └── middleware/
    │       └── auth.rs           // Token 验证中间件
    ├── ws/
    │   ├── mod.rs
    │   ├── server.rs             // WebSocket 升级入口
    │   ├── session.rs            // WsSession（单设备连接）
    │   └── frame.rs              // 帧类型/序列化/反序列化
    ├── session_manager.rs        // 全局 HashMap<DeviceId, WsSession>
    ├── dispatcher.rs             // HTTP → WS 命令调度
    └── metrics.rs                // Prometheus 指标
```

---

## 二、核心数据结构

### frame.rs — 帧定义

```rust
use bytes::{Buf, BufMut, Bytes, BytesMut};
use serde::{Deserialize, Serialize};
use uuid::Uuid;

#[repr(u8)]
#[derive(Debug, Clone, Copy, PartialEq)]
pub enum FrameType {
    Register     = 0x01,
    RegisterAck  = 0x02,
    Command      = 0x03,
    Response     = 0x04,
    StreamStart  = 0x05,
    StreamData   = 0x06,
    StreamStop   = 0x07,
    FilePush     = 0x08,
    FilePushAck  = 0x09,
    FilePullReq  = 0x0A,
    FilePullData = 0x0B,
    Ping         = 0x0C,
    Pong         = 0x0D,
    Error        = 0x0E,
}

#[derive(Debug, Clone)]
pub struct Frame {
    pub frame_type: FrameType,
    pub session_id: [u8; 16],   // UUID v4 bytes
    pub payload: Bytes,         // JSON UTF-8
}

impl Frame {
    pub fn encode(&self) -> Bytes {
        let mut buf = BytesMut::with_capacity(17 + self.payload.len());
        buf.put_u8(self.frame_type as u8);
        buf.put_slice(&self.session_id);
        buf.put_slice(&self.payload);
        buf.freeze()
    }

    pub fn decode(data: &[u8]) -> Result<Self, FrameError> {
        if data.len() < 17 {
            return Err(FrameError::TooShort);
        }
        let frame_type = FrameType::try_from(data[0])?;
        let mut session_id = [0u8; 16];
        session_id.copy_from_slice(&data[1..17]);
        let payload = Bytes::copy_from_slice(&data[17..]);
        Ok(Frame { frame_type, session_id, payload })
    }

    /// 构建 COMMAND 帧
    pub fn command(session_id: Uuid, payload: &impl Serialize) -> Self {
        Frame {
            frame_type: FrameType::Command,
            session_id: *session_id.as_bytes(),
            payload: Bytes::from(serde_json::to_vec(payload).unwrap()),
        }
    }
}

// Payload 结构体
#[derive(Serialize, Deserialize, Debug)]
pub struct CommandPayload {
    pub cmd: String,
    pub args: Option<String>,
    pub timeout: Option<u32>,
}

#[derive(Serialize, Deserialize, Debug)]
pub struct ResponsePayload {
    pub exit_code: i32,
    pub stdout: String,
    pub stderr: String,
    pub elapsed_ms: u64,
}

#[derive(Serialize, Deserialize, Debug)]
pub struct RegisterPayload {
    pub device_id: String,
    pub token: String,
    pub model: String,
    pub manufacturer: String,
    pub android_ver: String,
    pub sdk_int: u32,
    pub app_ver: String,
}
```

### session_manager.rs — 全局会话管理

```rust
use std::collections::HashMap;
use std::sync::Arc;
use tokio::sync::{oneshot, RwLock};
use uuid::Uuid;
use crate::ws::session::WsSession;

pub type DeviceId = String;
pub type SessionId = Uuid;

/// 等待 App 响应的挂起请求
pub struct PendingRequest {
    pub tx: oneshot::Sender<ResponsePayload>,
}

#[derive(Clone)]
pub struct SessionManager {
    inner: Arc<RwLock<Inner>>,
}

struct Inner {
    /// 设备连接映射: DeviceId → WsSession
    devices: HashMap<DeviceId, WsSession>,
    /// 挂起的命令请求: SessionId → PendingRequest
    pending: HashMap<SessionId, PendingRequest>,
}

impl SessionManager {
    pub fn new() -> Self {
        Self {
            inner: Arc::new(RwLock::new(Inner {
                devices: HashMap::new(),
                pending: HashMap::new(),
            })),
        }
    }

    /// 设备上线
    pub async fn register_device(&self, device_id: DeviceId, session: WsSession) {
        let mut inner = self.inner.write().await;
        inner.devices.insert(device_id, session);
    }

    /// 设备下线
    pub async fn remove_device(&self, device_id: &str) {
        let mut inner = self.inner.write().await;
        inner.devices.remove(device_id);
    }

    /// 检查设备是否在线
    pub async fn is_online(&self, device_id: &str) -> bool {
        self.inner.read().await.devices.contains_key(device_id)
    }

    /// 获取所有设备 ID
    pub async fn list_devices(&self) -> Vec<DeviceId> {
        self.inner.read().await.devices.keys().cloned().collect()
    }

    /// 发送帧到设备，并注册 pending 请求
    pub async fn dispatch(
        &self,
        device_id: &str,
        session_id: Uuid,
        frame: Frame,
    ) -> Result<oneshot::Receiver<ResponsePayload>, DispatchError> {
        let (tx, rx) = oneshot::channel();
        let mut inner = self.inner.write().await;

        let session = inner.devices.get(device_id)
            .ok_or(DispatchError::DeviceOffline)?;

        session.send_frame(frame).await?;
        inner.pending.insert(session_id, PendingRequest { tx });
        Ok(rx)
    }

    /// App 响应到来时，通知 HTTP handler
    pub async fn resolve_pending(&self, session_id: Uuid, response: ResponsePayload) {
        let mut inner = self.inner.write().await;
        if let Some(req) = inner.pending.remove(&session_id) {
            let _ = req.tx.send(response);
        }
    }
}
```

### dispatcher.rs — 命令调度

```rust
use tokio::time::{timeout, Duration};
use uuid::Uuid;
use crate::session_manager::{SessionManager, DispatchError};
use crate::ws::frame::{Frame, CommandPayload, ResponsePayload};
use crate::error::AppError;

pub struct Dispatcher {
    session_mgr: SessionManager,
}

impl Dispatcher {
    pub fn new(session_mgr: SessionManager) -> Self {
        Self { session_mgr }
    }

    /// 发送命令并等待响应，带超时
    pub async fn execute_command(
        &self,
        device_id: &str,
        cmd: CommandPayload,
        timeout_secs: u64,
    ) -> Result<ResponsePayload, AppError> {
        let session_id = Uuid::new_v4();
        let frame = Frame::command(session_id, &cmd);

        let rx = self.session_mgr
            .dispatch(device_id, session_id, frame)
            .await
            .map_err(|e| match e {
                DispatchError::DeviceOffline => AppError::DeviceOffline,
                DispatchError::SendError => AppError::InternalError("send failed".into()),
            })?;

        timeout(Duration::from_secs(timeout_secs), rx)
            .await
            .map_err(|_| AppError::CommandTimeout)?
            .map_err(|_| AppError::InternalError("channel dropped".into()))
    }
}
```

---

## 三、WebSocket 处理（ws/server.rs）

```rust
use axum::extract::{State, WebSocketUpgrade, ws::WebSocket};
use axum::response::Response;
use crate::session_manager::SessionManager;
use crate::ws::session::WsSession;
use crate::ws::frame::{Frame, FrameType};

pub async fn ws_handler(
    ws: WebSocketUpgrade,
    headers: HeaderMap,
    State(mgr): State<SessionManager>,
) -> Result<Response, AppError> {
    // 验证 Token
    let token = extract_token(&headers)?;
    validate_token(&token)?;

    Ok(ws.on_upgrade(move |socket| handle_socket(socket, mgr)))
}

async fn handle_socket(socket: WebSocket, mgr: SessionManager) {
    let (sender, mut receiver) = socket.split();
    let session = WsSession::new(sender);

    let mut device_id: Option<String> = None;

    while let Some(msg) = receiver.next().await {
        match msg {
            Ok(Message::Binary(data)) => {
                let frame = match Frame::decode(&data) {
                    Ok(f) => f,
                    Err(e) => { tracing::warn!("bad frame: {e}"); continue; }
                };

                match frame.frame_type {
                    FrameType::Register => {
                        // 解析设备信息，注册到 SessionManager
                        if let Ok(reg) = serde_json::from_slice::<RegisterPayload>(&frame.payload) {
                            device_id = Some(reg.device_id.clone());
                            mgr.register_device(reg.device_id.clone(), session.clone()).await;
                            // 发送 REGISTER_ACK
                            let ack = Frame::register_ack("ok");
                            let _ = session.send_frame(ack).await;
                        }
                    }
                    FrameType::Response => {
                        // 将响应路由到等待的 HTTP handler
                        let session_id = Uuid::from_bytes(frame.session_id);
                        if let Ok(resp) = serde_json::from_slice(&frame.payload) {
                            mgr.resolve_pending(session_id, resp).await;
                        }
                    }
                    FrameType::StreamData => {
                        let session_id = Uuid::from_bytes(frame.session_id);
                        mgr.deliver_stream_chunk(session_id, frame.payload).await;
                    }
                    FrameType::Ping => {
                        let pong = Frame::pong();
                        let _ = session.send_frame(pong).await;
                    }
                    _ => {}
                }
            }
            Ok(Message::Close(_)) | Err(_) => break,
            _ => {}
        }
    }

    // 设备下线清理
    if let Some(id) = device_id {
        mgr.remove_device(&id).await;
        tracing::info!("device disconnected: {id}");
    }
}
```

---

## 四、HTTP Handlers（示例：shell.rs）

```rust
use axum::{
    extract::{Path, State, Json},
    http::StatusCode,
    response::IntoResponse,
};
use serde::{Deserialize, Serialize};
use crate::dispatcher::Dispatcher;
use crate::error::AppError;

#[derive(Deserialize)]
pub struct ShellRequest {
    pub command: String,
    #[serde(default = "default_timeout")]
    pub timeout: u64,
}

fn default_timeout() -> u64 { 30 }

#[derive(Serialize)]
pub struct ShellResponse {
    pub device_id: String,
    pub session_id: String,
    pub exit_code: i32,
    pub stdout: String,
    pub stderr: String,
    pub elapsed_ms: u64,
}

pub async fn shell_handler(
    Path(device_id): Path<String>,
    State(dispatcher): State<Dispatcher>,
    Json(req): Json<ShellRequest>,
) -> Result<impl IntoResponse, AppError> {
    let result = dispatcher.execute_command(
        &device_id,
        CommandPayload {
            cmd: "shell".into(),
            args: Some(req.command),
            timeout: Some(req.timeout as u32),
        },
        req.timeout,
    ).await?;

    Ok(Json(ShellResponse {
        device_id,
        session_id: Uuid::new_v4().to_string(),
        exit_code: result.exit_code,
        stdout: result.stdout,
        stderr: result.stderr,
        elapsed_ms: result.elapsed_ms,
    }))
}
```

---

## 五、main.rs — 启动入口

```rust
#[tokio::main]
async fn main() -> anyhow::Result<()> {
    // 初始化日志
    tracing_subscriber::fmt()
        .with_env_filter(EnvFilter::from_default_env())
        .init();

    // 加载配置
    let config = Config::load()?;

    // 初始化全局状态
    let session_mgr = SessionManager::new();
    let dispatcher = Dispatcher::new(session_mgr.clone());

    // 构建路由
    let app = Router::new()
        // 设备 WebSocket 连接
        .route("/ws/device", get(ws_handler))
        // HTTP API
        .route("/api/v1/devices", get(handlers::devices::list))
        .route("/api/v1/devices/:id", get(handlers::devices::get))
        .route("/api/v1/devices/:id/shell", post(handlers::shell::shell_handler))
        .route("/api/v1/devices/:id/screenshot", get(handlers::screenshot::screenshot_handler))
        .route("/api/v1/devices/:id/install", post(handlers::install::install_handler))
        .route("/api/v1/devices/:id/logcat", get(handlers::logcat::logcat_handler))
        .route("/api/v1/devices/:id/push", post(handlers::push::push_handler))
        .route("/api/v1/devices/:id/pull", get(handlers::pull::pull_handler))
        .route("/health", get(health_handler))
        // 中间件
        .layer(middleware::from_fn_with_state(config.clone(), auth_middleware))
        .with_state(AppState { session_mgr, dispatcher, config: config.clone() });

    // 启动服务
    let addr = SocketAddr::from(([0, 0, 0, 0], config.port));
    tracing::info!("ADBTunnel Server listening on {}", addr);
    axum::Server::bind(&addr)
        .serve(app.into_make_service())
        .await?;

    Ok(())
}
```

---

## 六、Cargo.toml 依赖

```toml
[package]
name = "adbtunnel-server"
version = "0.1.0"
edition = "2021"

[dependencies]
tokio = { version = "1", features = ["full"] }
axum = { version = "0.7", features = ["ws", "multipart"] }
tokio-tungstenite = { version = "0.21", features = ["native-tls"] }
tower = { version = "0.4", features = ["full"] }
tower-http = { version = "0.5", features = ["cors", "trace"] }

serde = { version = "1", features = ["derive"] }
serde_json = "1"
uuid = { version = "1", features = ["v4"] }
bytes = "1"

tracing = "0.1"
tracing-subscriber = { version = "0.3", features = ["env-filter"] }

anyhow = "1"
thiserror = "1"
config = "0.14"

# 指标
prometheus = "0.13"
axum-prometheus = "0.6"

# TLS
rustls = "0.22"
tokio-rustls = "0.25"
```

---

## 七、配置文件（config/default.toml）

```toml
[server]
host = "0.0.0.0"
port = 8080
tls_cert = ""          # 留空则不启用 TLS（建议在 Nginx 做终止）
tls_key = ""

[auth]
# 统一 Token（设备注册和 API 调用共用）
token = "changeme-replace-with-openssl-rand-hex-32"

[limits]
max_devices = 1000
command_timeout_default = 30    # 秒
command_timeout_max = 300       # 秒
max_upload_size = 524288000     # 500 MB
ws_ping_interval = 30           # 秒
ws_ping_timeout = 10            # 秒

[log]
level = "info"
```
