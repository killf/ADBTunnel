mod config;
mod dispatcher;
mod error;
mod http;
mod session_manager;
mod ws;

use axum::{
    routing::{get, post},
    Router,
};
use tower_http::{cors::CorsLayer, trace::TraceLayer};
use tracing_subscriber::{layer::SubscriberExt, util::SubscriberInitExt, EnvFilter};

use config::Config;
use dispatcher::Dispatcher;
use session_manager::SessionManager;

use http::handlers::{devices, health, input, screenshot, shell};
use ws::server::ws_handler;

#[derive(Clone)]
pub struct AppState {
    pub mgr: SessionManager,
    pub dispatcher: Dispatcher,
}

#[tokio::main]
async fn main() -> anyhow::Result<()> {
    tracing_subscriber::registry()
        .with(EnvFilter::try_from_default_env().unwrap_or_else(|_| "info".into()))
        .with(tracing_subscriber::fmt::layer())
        .init();

    let cfg = Config::from_env();

    let mgr = SessionManager::new();
    let dispatcher = Dispatcher::new(mgr.clone());
    let state = AppState { mgr, dispatcher };

    let app = Router::new()
        // WebSocket endpoint for Android devices
        .route("/ws/device", get(ws_handler))
        // HTTP API
        .route("/health", get(health::health_handler))
        .route("/api/v1/devices", get(devices::list_handler))
        .route("/api/v1/devices/:id", get(devices::get_handler))
        .route("/api/v1/devices/:id/shell", post(shell::shell_handler))
        .route("/api/v1/devices/:id/screenshot", get(screenshot::screenshot_handler))
        .route("/api/v1/devices/:id/input", post(input::input_handler))
        .layer(CorsLayer::permissive())
        .layer(TraceLayer::new_for_http())
        .with_state(state);

    let addr = cfg.bind_addr();
    tracing::info!("ADBTunnel Server listening on {}", addr);

    let listener = tokio::net::TcpListener::bind(&addr).await?;
    axum::serve(listener, app).await?;

    Ok(())
}
