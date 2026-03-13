use axum::{extract::State, Json};
use serde_json::{json, Value};

use crate::AppState;

pub async fn list_handler(State(state): State<AppState>) -> Json<Value> {
    let devices = state.mgr.list_devices().await;
    let total = devices.len();
    let list: Vec<Value> = devices
        .into_iter()
        .map(|d| {
            json!({
                "device_id": d.device_id,
                "model": d.model,
                "manufacturer": d.manufacturer,
                "android_ver": d.android_ver,
                "sdk_int": d.sdk_int,
                "app_ver": d.app_ver,
                "status": "online",
                "connected_at": d.connected_at,
            })
        })
        .collect();
    Json(json!({ "devices": list, "total": total }))
}

pub async fn get_handler(
    axum::extract::Path(device_id): axum::extract::Path<String>,
    State(state): State<AppState>,
) -> Result<Json<Value>, crate::error::AppError> {
    let d = state.mgr.get_device(&device_id).await
        .ok_or(crate::error::AppError::DeviceNotFound)?;
    Ok(Json(json!({
        "device_id": d.device_id,
        "model": d.model,
        "manufacturer": d.manufacturer,
        "android_ver": d.android_ver,
        "sdk_int": d.sdk_int,
        "app_ver": d.app_ver,
        "status": "online",
        "connected_at": d.connected_at,
    })))
}
