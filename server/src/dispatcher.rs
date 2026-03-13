use tokio::time::{timeout, Duration};
use uuid::Uuid;

use crate::error::AppError;
use crate::session_manager::SessionManager;
use crate::ws::frame::{CommandPayload, Frame, ResponsePayload};

#[derive(Clone)]
pub struct Dispatcher {
    mgr: SessionManager,
}

impl Dispatcher {
    pub fn new(mgr: SessionManager) -> Self {
        Self { mgr }
    }

    pub async fn execute(
        &self,
        device_id: &str,
        payload: CommandPayload,
        timeout_secs: u64,
    ) -> Result<ResponsePayload, AppError> {
        let session_id = Uuid::new_v4();
        let frame = Frame::command(session_id, &payload);

        let rx = self.mgr.dispatch(device_id, session_id, frame).await?;

        timeout(Duration::from_secs(timeout_secs), rx)
            .await
            .map_err(|_| AppError::CommandTimeout)?
            .map_err(|_| AppError::Internal("channel closed".into()))
    }
}
