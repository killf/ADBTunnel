use axum::{
    extract::{Path, State},
    response::{IntoResponse, Response},
    http::header,
};

use crate::error::AppError;
use crate::ws::frame::CommandPayload;
use crate::AppState;

pub async fn screenshot_handler(
    Path(device_id): Path<String>,
    State(state): State<AppState>,
) -> Result<Response, AppError> {
    let result = state.dispatcher.execute(
        &device_id,
        CommandPayload {
            cmd: "screencap".into(),
            action: None,
            args: None,
            timeout: Some(15),
        },
        15,
    ).await?;

    if result.exit_code != 0 {
        return Err(AppError::Internal(result.stderr));
    }

    // stdout is base64-encoded PNG
    let png = base64::Engine::decode(
        &base64::engine::general_purpose::STANDARD,
        result.stdout.trim(),
    ).map_err(|e| AppError::Internal(format!("base64 decode: {e}")))?;

    Ok((
        [(header::CONTENT_TYPE, "image/png")],
        png,
    ).into_response())
}
