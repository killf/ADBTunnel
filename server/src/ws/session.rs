use std::sync::Arc;
use axum::extract::ws::{Message, WebSocket};
use futures_util::stream::SplitSink;
use futures_util::SinkExt;
use tokio::sync::Mutex;
use crate::ws::frame::Frame;

#[derive(Clone, Debug)]
pub struct WsSession {
    sender: Arc<Mutex<SplitSink<WebSocket, Message>>>,
}

impl WsSession {
    pub fn new(sender: SplitSink<WebSocket, Message>) -> Self {
        Self {
            sender: Arc::new(Mutex::new(sender)),
        }
    }

    pub async fn send_frame(&self, frame: Frame) -> bool {
        let bytes = frame.encode();
        let mut sender = self.sender.lock().await;
        sender.send(Message::Binary(bytes.to_vec())).await.is_ok()
    }
}
