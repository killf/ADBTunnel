use axum::{extract::State, Json};
use serde_json::json;

use crate::AppState;

pub async fn health_handler(State(state): State<AppState>) -> Json<serde_json::Value> {
    let count = state.mgr.list_devices().await.len();
    Json(json!({
        "status": "ok",
        "online_devices": count,
    }))
}
