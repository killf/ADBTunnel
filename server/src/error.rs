use axum::{
    http::StatusCode,
    response::{IntoResponse, Response},
    Json,
};
use serde_json::json;
use thiserror::Error;

#[derive(Error, Debug)]
pub enum AppError {
    #[error("device offline")]
    DeviceOffline,

    #[error("device not found")]
    DeviceNotFound,

    #[error("command timeout")]
    CommandTimeout,

    #[error("internal error: {0}")]
    Internal(String),
}

impl IntoResponse for AppError {
    fn into_response(self) -> Response {
        let (status, error) = match &self {
            AppError::DeviceOffline => (StatusCode::SERVICE_UNAVAILABLE, "device_offline"),
            AppError::DeviceNotFound => (StatusCode::NOT_FOUND, "device_not_found"),
            AppError::CommandTimeout => (StatusCode::REQUEST_TIMEOUT, "command_timeout"),
            AppError::Internal(_) => (StatusCode::INTERNAL_SERVER_ERROR, "internal_error"),
        };
        (status, Json(json!({ "error": error, "message": self.to_string() }))).into_response()
    }
}
