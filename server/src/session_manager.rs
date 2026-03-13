use std::collections::HashMap;
use std::sync::Arc;
use tokio::sync::{oneshot, RwLock};
use uuid::Uuid;
use serde::Serialize;

use crate::ws::frame::{RegisterPayload, ResponsePayload};
use crate::ws::session::WsSession;
use crate::error::AppError;

/// Metadata stored for each connected device
#[derive(Clone, Debug, Serialize)]
pub struct DeviceEntry {
    pub device_id: String,
    pub model: String,
    pub manufacturer: String,
    pub android_ver: String,
    pub sdk_int: u32,
    pub app_ver: String,
    pub connected_at: u64,
    #[serde(skip)]
    pub session: WsSession,
}

impl DeviceEntry {
    pub fn new(reg: RegisterPayload, session: WsSession) -> Self {
        let now = std::time::SystemTime::now()
            .duration_since(std::time::UNIX_EPOCH)
            .unwrap_or_default()
            .as_millis() as u64;
        Self {
            device_id: reg.device_id,
            model: reg.model,
            manufacturer: reg.manufacturer,
            android_ver: reg.android_ver,
            sdk_int: reg.sdk_int,
            app_ver: reg.app_ver,
            connected_at: now,
            session,
        }
    }
}

struct Inner {
    devices: HashMap<String, DeviceEntry>,
    pending: HashMap<Uuid, oneshot::Sender<ResponsePayload>>,
}

#[derive(Clone)]
pub struct SessionManager {
    inner: Arc<RwLock<Inner>>,
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

    pub async fn register_device(&self, reg: RegisterPayload, session: WsSession) {
        let entry = DeviceEntry::new(reg, session);
        let mut g = self.inner.write().await;
        tracing::info!("device connected: {} ({})", entry.device_id, entry.model);
        g.devices.insert(entry.device_id.clone(), entry);
    }

    pub async fn remove_device(&self, device_id: &str) {
        let mut g = self.inner.write().await;
        if g.devices.remove(device_id).is_some() {
            tracing::info!("device disconnected: {}", device_id);
        }
    }

    pub async fn list_devices(&self) -> Vec<DeviceEntry> {
        self.inner.read().await.devices.values().cloned().collect()
    }

    pub async fn get_device(&self, device_id: &str) -> Option<DeviceEntry> {
        self.inner.read().await.devices.get(device_id).cloned()
    }

    /// Send a command frame and register a pending waiter. Returns the response receiver.
    pub async fn dispatch(
        &self,
        device_id: &str,
        session_id: Uuid,
        frame: crate::ws::frame::Frame,
    ) -> Result<oneshot::Receiver<ResponsePayload>, AppError> {
        let (tx, rx) = oneshot::channel();
        let mut g = self.inner.write().await;
        let entry = g.devices.get(device_id).ok_or(AppError::DeviceOffline)?;
        let session = entry.session.clone();
        // Register pending before sending to avoid race
        g.pending.insert(session_id, tx);
        drop(g);
        if !session.send_frame(frame).await {
            // Remove pending if send failed
            self.inner.write().await.pending.remove(&session_id);
            return Err(AppError::DeviceOffline);
        }
        Ok(rx)
    }

    /// Called by WS handler when a RESPONSE frame arrives
    pub async fn resolve(&self, session_id: Uuid, resp: ResponsePayload) {
        let mut g = self.inner.write().await;
        if let Some(tx) = g.pending.remove(&session_id) {
            let _ = tx.send(resp);
        }
    }
}
