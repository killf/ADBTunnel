use axum::{
    extract::{Path, State},
    Json,
};
use serde_json::{json, Value};

use crate::error::AppError;
use crate::ws::frame::CommandPayload;
use crate::AppState;

/// POST /api/v1/devices/:id/input
/// Body is passed directly as the args to the input executor on the device.
pub async fn input_handler(
    Path(device_id): Path<String>,
    State(state): State<AppState>,
    Json(body): Json<Value>,
) -> Result<Json<Value>, AppError> {
    // Extract timeout from body if provided, default 10s
    let timeout = body.get("timeout")
        .and_then(|v| v.as_u64())
        .unwrap_or(10)
        .min(60);

    let result = state.dispatcher.execute(
        &device_id,
        CommandPayload {
            cmd: "input".into(),
            action: body.get("action").and_then(|v| v.as_str()).map(str::to_string),
            args: Some(body),
            timeout: Some(timeout as u32),
        },
        timeout,
    ).await?;

    Ok(Json(json!({
        "device_id": device_id,
        "exit_code": result.exit_code,
        "stdout": result.stdout,
        "stderr": result.stderr,
        "elapsed_ms": result.elapsed_ms,
    })))
}
