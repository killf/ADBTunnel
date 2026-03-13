use bytes::Bytes;
use serde::{Deserialize, Serialize};

#[repr(u8)]
#[derive(Debug, Clone, Copy, PartialEq)]
pub enum FrameType {
    Register    = 0x01,
    RegisterAck = 0x02,
    Command     = 0x03,
    Response    = 0x04,
    StreamStart = 0x05,
    StreamData  = 0x06,
    StreamStop  = 0x07,
    FilePush    = 0x08,
    FilePushAck = 0x09,
    FilePullReq = 0x0A,
    FilePullData= 0x0B,
    Ping        = 0x0C,
    Pong        = 0x0D,
    Error       = 0x0E,
}

impl TryFrom<u8> for FrameType {
    type Error = ();
    fn try_from(v: u8) -> Result<Self, ()> {
        match v {
            0x01 => Ok(Self::Register),
            0x02 => Ok(Self::RegisterAck),
            0x03 => Ok(Self::Command),
            0x04 => Ok(Self::Response),
            0x05 => Ok(Self::StreamStart),
            0x06 => Ok(Self::StreamData),
            0x07 => Ok(Self::StreamStop),
            0x08 => Ok(Self::FilePush),
            0x09 => Ok(Self::FilePushAck),
            0x0A => Ok(Self::FilePullReq),
            0x0B => Ok(Self::FilePullData),
            0x0C => Ok(Self::Ping),
            0x0D => Ok(Self::Pong),
            0x0E => Ok(Self::Error),
            _ => Err(()),
        }
    }
}

#[derive(Debug, Clone)]
pub struct Frame {
    pub frame_type: FrameType,
    pub session_id: [u8; 16],
    pub payload: Bytes,
}

impl Frame {
    pub fn encode(&self) -> Bytes {
        let mut buf = Vec::with_capacity(17 + self.payload.len());
        buf.push(self.frame_type as u8);
        buf.extend_from_slice(&self.session_id);
        buf.extend_from_slice(&self.payload);
        Bytes::from(buf)
    }

    pub fn decode(data: &[u8]) -> Option<Self> {
        if data.len() < 17 {
            return None;
        }
        let frame_type = FrameType::try_from(data[0]).ok()?;
        let mut session_id = [0u8; 16];
        session_id.copy_from_slice(&data[1..17]);
        let payload = Bytes::copy_from_slice(&data[17..]);
        Some(Frame { frame_type, session_id, payload })
    }

    pub fn command(session_id: uuid::Uuid, payload: &impl Serialize) -> Self {
        Frame {
            frame_type: FrameType::Command,
            session_id: *session_id.as_bytes(),
            payload: Bytes::from(serde_json::to_vec(payload).unwrap()),
        }
    }

    pub fn register_ack() -> Self {
        let payload = serde_json::json!({
            "status": "ok",
            "server_time": std::time::SystemTime::now()
                .duration_since(std::time::UNIX_EPOCH)
                .unwrap_or_default()
                .as_millis() as u64,
            "message": "registered",
            "protocol_ver": "1",
            "features": ["shell", "screencap", "input"]
        });
        Frame {
            frame_type: FrameType::RegisterAck,
            session_id: [0u8; 16],
            payload: Bytes::from(serde_json::to_vec(&payload).unwrap()),
        }
    }

    pub fn pong() -> Self {
        Frame {
            frame_type: FrameType::Pong,
            session_id: [0u8; 16],
            payload: Bytes::new(),
        }
    }
}

// ─── Payload types ────────────────────────────────────────────────────────────

#[derive(Serialize, Deserialize, Debug, Clone)]
pub struct CommandPayload {
    pub cmd: String,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub action: Option<String>,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub args: Option<serde_json::Value>,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub timeout: Option<u32>,
}

#[derive(Serialize, Deserialize, Debug, Clone)]
pub struct ResponsePayload {
    pub exit_code: i32,
    pub stdout: String,
    pub stderr: String,
    pub elapsed_ms: u64,
}

#[derive(Serialize, Deserialize, Debug, Clone)]
pub struct RegisterPayload {
    pub device_id: String,
    pub model: String,
    pub manufacturer: String,
    pub android_ver: String,
    pub sdk_int: u32,
    pub app_ver: String,
}
