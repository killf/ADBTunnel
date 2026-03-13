use axum::extract::ws::{Message, WebSocket};
use axum::extract::{State, WebSocketUpgrade};
use axum::response::Response;
use futures_util::StreamExt;
use uuid::Uuid;

use crate::session_manager::SessionManager;
use crate::ws::frame::{Frame, FrameType, RegisterPayload, ResponsePayload};
use crate::ws::session::WsSession;
use crate::AppState;

pub async fn ws_handler(
    ws: WebSocketUpgrade,
    State(state): State<AppState>,
) -> Response {
    ws.on_upgrade(move |socket| handle_socket(socket, state.mgr))
}

async fn handle_socket(socket: WebSocket, mgr: SessionManager) {
    let (sender, mut receiver) = socket.split();
    let session = WsSession::new(sender);
    let mut device_id: Option<String> = None;

    while let Some(Ok(msg)) = receiver.next().await {
        match msg {
            Message::Binary(data) => {
                let Some(frame) = Frame::decode(&data) else {
                    tracing::warn!("received malformed frame ({} bytes)", data.len());
                    continue;
                };

                match frame.frame_type {
                    FrameType::Register => {
                        match serde_json::from_slice::<RegisterPayload>(&frame.payload) {
                            Ok(reg) => {
                                device_id = Some(reg.device_id.clone());
                                mgr.register_device(reg, session.clone()).await;
                                session.send_frame(Frame::register_ack()).await;
                            }
                            Err(e) => tracing::warn!("invalid REGISTER payload: {e}"),
                        }
                    }

                    FrameType::Response => {
                        let sid = Uuid::from_bytes(frame.session_id);
                        match serde_json::from_slice::<ResponsePayload>(&frame.payload) {
                            Ok(resp) => mgr.resolve(sid, resp).await,
                            Err(e) => tracing::warn!("invalid RESPONSE payload: {e}"),
                        }
                    }

                    FrameType::Ping => {
                        session.send_frame(Frame::pong()).await;
                    }

                    _ => {}
                }
            }

            Message::Close(_) => break,
            _ => {}
        }
    }

    if let Some(id) = device_id {
        mgr.remove_device(&id).await;
    }
}
