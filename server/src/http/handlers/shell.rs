use axum::{
    extract::{Path, State},
    Json,
};
use serde::Deserialize;
use serde_json::json;

use crate::error::AppError;
use crate::ws::frame::CommandPayload;
use crate::AppState;

#[derive(Deserialize)]
pub struct ShellRequest {
    pub command: String,
    #[serde(default = "default_timeout")]
    pub timeout: u64,
}

fn default_timeout() -> u64 { 30 }

pub async fn shell_handler(
    Path(device_id): Path<String>,
    State(state): State<AppState>,
    Json(req): Json<ShellRequest>,
) -> Result<Json<serde_json::Value>, AppError> {
    let timeout = req.timeout.min(300);
    let result = state.dispatcher.execute(
        &device_id,
        CommandPayload {
            cmd: "shell".into(),
            action: None,
            args: Some(serde_json::Value::String(req.command)),
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
