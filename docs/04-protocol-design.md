# ADBTunnel — 通信协议设计

## 一、WebSocket 帧格式

所有 WebSocket 消息使用 **Binary Frame**（操作码 0x2），采用以下固定帧头 + 可变 Payload 结构：

```
┌─────────────────────────────────────────────────────────────────┐
│                        ADBTunnel WS Frame                        │
├──────────┬────────────────────────────────┬─────────────────────┤
│  type    │          session_id            │       payload       │
│  1 byte  │          16 bytes (UUID v4)    │   variable (JSON)   │
├──────────┼────────────────────────────────┼─────────────────────┤
│  0x01    │  00 00 00 ... (全0 = 无会话)   │   {"...": "..."}    │
└──────────┴────────────────────────────────┴─────────────────────┘

全帧 = 1 + 16 + len(payload) bytes
```

### 帧类型定义

| 类型值 | 名称 | 方向 | 说明 |
|--------|------|------|------|
| `0x01` | `REGISTER` | App → Server | 设备注册 |
| `0x02` | `REGISTER_ACK` | Server → App | 注册确认 |
| `0x03` | `COMMAND` | Server → App | 执行命令 |
| `0x04` | `RESPONSE` | App → Server | 命令结果（一次性） |
| `0x05` | `STREAM_START` | Server → App | 开始流式命令 |
| `0x06` | `STREAM_DATA` | App → Server | 流式数据块 |
| `0x07` | `STREAM_STOP` | Server → App | 停止流式命令 |
| `0x08` | `FILE_PUSH` | Server → App | 推送文件（分块） |
| `0x09` | `FILE_PUSH_ACK` | App → Server | 文件推送确认 |
| `0x0A` | `FILE_PULL_REQ` | Server → App | 请求拉取文件 |
| `0x0B` | `FILE_PULL_DATA` | App → Server | 文件内容（分块） |
| `0x0C` | `PING` | App → Server | 心跳探测 |
| `0x0D` | `PONG` | Server → App | 心跳响应 |
| `0x0E` | `ERROR` | 双向 | 错误通知 |

---

## 二、各帧 Payload 结构

### REGISTER (0x01)
```json
{
  "device_id": "550e8400-e29b-41d4-a716-446655440000",
  "token": "Bearer xxxx",
  "model": "Pixel 7",
  "manufacturer": "Google",
  "android_ver": "13",
  "sdk_int": 33,
  "app_ver": "1.0.0"
}
```

### REGISTER_ACK (0x02)
```json
{
  "status": "ok",
  "server_time": 1710000000000,
  "message": "registered"
}
```
错误时：
```json
{
  "status": "error",
  "code": "AUTH_FAILED",
  "message": "invalid token"
}
```

### COMMAND (0x03)
```json
{
  "cmd": "shell",
  "args": "ls -la /sdcard/",
  "timeout": 30,
  "env": {"KEY": "VALUE"}
}
```
`cmd` 可取值：`shell` / `screencap` / `screenrecord` / `install` / `uninstall` / `getprop` / `dumpsys` / `input`

#### 当 cmd = "input" 时，payload 按 action 类型扩展：

**单击（tap）**
```json
{ "cmd": "input", "action": "tap", "x": 540, "y": 960 }
```

**双击（double_tap）**
```json
{ "cmd": "input", "action": "double_tap", "x": 540, "y": 960, "interval_ms": 100 }
```
`interval_ms`：两次点击间隔，默认 100ms。

**长按（long_press）**
```json
{ "cmd": "input", "action": "long_press", "x": 540, "y": 960, "duration_ms": 1000 }
```
`duration_ms`：按压时长，默认 1000ms。

**滑动（swipe）**
```json
{
  "cmd": "input", "action": "swipe",
  "from_x": 540, "from_y": 1600,
  "to_x": 540, "to_y": 400,
  "duration_ms": 300
}
```
`duration_ms`：滑动总时长，默认 300ms，值越小越快。

**按键（key）**
```json
{ "cmd": "input", "action": "key", "key": "home" }
```

`key` 支持的字符串别名：

| 别名 | KEYCODE | 说明 |
|------|---------|------|
| `home` | `KEYCODE_HOME` | Home 键 |
| `back` | `KEYCODE_BACK` | 返回键 |
| `menu` | `KEYCODE_MENU` | 菜单键 |
| `power` | `KEYCODE_POWER` | 电源键 |
| `volume_up` | `KEYCODE_VOLUME_UP` | 音量+ |
| `volume_down` | `KEYCODE_VOLUME_DOWN` | 音量- |
| `recent` | `KEYCODE_APP_SWITCH` | 最近任务键 |
| `enter` | `KEYCODE_ENTER` | 回车键 |
| `delete` | `KEYCODE_DEL` | 删除键 |
| `escape` | `KEYCODE_ESCAPE` | ESC 键 |
| `screenshot` | `KEYCODE_SYSRQ` | 截屏快捷键组合（部分机型） |
| `notification` | `KEYCODE_NOTIFICATION` | 通知栏 |

也可直接传 Android KeyEvent 整数值：`"key": 26`（等价于 `power`）。

**输入文本（text）**
```json
{ "cmd": "input", "action": "text", "text": "Hello 你好", "method": "auto" }
```

`method` 可选值：

| method | 行为 | 适用场景 |
|--------|------|---------|
| `auto`（默认）| 自动判断：纯 ASCII 用 `input text`，含非 ASCII 自动切换 `clipboard` | 通用 |
| `ascii` | 直接调用 `input text`，速度最快 | 仅英文/数字 |
| `clipboard` | App 通过 ClipboardManager 写剪贴板，再触发 PASTE | 中文/特殊符号 |

**剪贴板操作（clipboard）**

写入剪贴板：
```json
{ "cmd": "input", "action": "clipboard_set", "text": "要复制的内容" }
```

读取剪贴板（返回当前内容）：
```json
{ "cmd": "input", "action": "clipboard_get" }
```
响应 payload 中的 `stdout` 为剪贴板文本；Android 10+ 后台读取受限，返回空串时属正常行为。

**复制（copy）**

触发系统复制快捷键（Ctrl+C / KEYCODE_COPY），然后读取剪贴板内容：
```json
{ "cmd": "input", "action": "copy" }
```
响应：`{ "exit_code": 0, "stdout": "<被复制的文本>", ... }`

**粘贴（paste）**

触发系统粘贴快捷键（Ctrl+V / KEYCODE_PASTE）：
```json
{ "cmd": "input", "action": "paste" }
```
也可先用 `clipboard_set` 设置内容再 `paste`，实现"远程填字"：
```json
// 批量: 先设剪贴板，再粘贴
[
  { "action": "clipboard_set", "text": "远程填入的内容" },
  { "action": "paste" }
]
```

### RESPONSE (0x04)
```json
{
  "exit_code": 0,
  "stdout": "total 0\ndrwxrwx--x ...",
  "stderr": "",
  "elapsed_ms": 245
}
```

### STREAM_START (0x05)
```json
{
  "cmd": "logcat",
  "args": "-v brief *:W"
}
```

### STREAM_DATA (0x06)
```json
{
  "chunk": "W/MainActivity(12345): warning message\n",
  "seq": 42
}
```

### STREAM_STOP (0x07)
```json
{
  "reason": "client_cancelled"
}
```

### FILE_PUSH (0x08)（分块传输）
```json
{
  "dest": "/sdcard/test.apk",
  "total_size": 1048576,
  "chunk_index": 0,
  "total_chunks": 16,
  "data": "<base64 encoded chunk>",
  "md5": "abc123..."
}
```
最后一块 `chunk_index == total_chunks - 1` 时 App 组装文件并执行安装。

### FILE_PUSH_ACK (0x09)
```json
{
  "success": true,
  "message": "file written",
  "bytes_written": 65536
}
```

### ERROR (0x0E)
```json
{
  "code": "EXEC_TIMEOUT",
  "message": "command timed out after 30s"
}
```

---

## 三、HTTP API 设计

### 基础信息

- Base URL: `https://server.example.com/api/v1`
- 认证: `Authorization: Bearer <api_token>`
- 响应格式: `application/json`
- 所有时间戳: Unix milliseconds

### 3.1 设备管理

#### 获取设备列表
```
GET /devices

Response 200:
{
  "devices": [
    {
      "device_id": "550e8400-...",
      "model": "Pixel 7",
      "manufacturer": "Google",
      "android_ver": "13",
      "status": "online",       // online / offline
      "connected_at": 1710000000000,
      "last_ping_at": 1710000100000
    }
  ],
  "total": 1
}
```

#### 获取单设备信息
```
GET /devices/{device_id}

Response 200:
{
  "device_id": "550e8400-...",
  "model": "Pixel 7",
  "status": "online",
  ...
}

Response 404:
{"error": "device_not_found"}
```

### 3.2 Shell 命令

```
POST /devices/{device_id}/shell

Request:
{
  "command": "ls -la /sdcard/",
  "timeout": 30              // 秒，默认 30，最大 300
}

Response 200:
{
  "device_id": "550e8400-...",
  "session_id": "abc-def-...",
  "exit_code": 0,
  "stdout": "total 0\n...",
  "stderr": "",
  "elapsed_ms": 245
}

Response 408:
{"error": "command_timeout", "session_id": "..."}

Response 503:
{"error": "device_offline"}
```

### 3.3 截图

```
GET /devices/{device_id}/screenshot

Response 200:
Content-Type: image/png
Body: <PNG binary>

或者加 ?format=base64 返回 JSON:
{
  "data": "<base64>",
  "width": 1080,
  "height": 2400,
  "elapsed_ms": 890
}
```

### 3.4 APK 安装

```
POST /devices/{device_id}/install
Content-Type: multipart/form-data

Fields:
  file: <APK binary>
  options: "-r -t"           // 可选，pm install 选项

Response 200:
{
  "success": true,
  "message": "Success",
  "package_name": "com.example.app",
  "elapsed_ms": 5320
}

Response 422:
{
  "success": false,
  "message": "INSTALL_FAILED_VERSION_DOWNGRADE",
  "elapsed_ms": 1200
}
```

### 3.5 APK 卸载

```
DELETE /devices/{device_id}/packages/{package_name}

Response 200:
{
  "success": true,
  "message": "Success"
}
```

### 3.6 文件推送（Push）

```
POST /devices/{device_id}/push
Content-Type: multipart/form-data

Fields:
  file: <binary>
  dest: "/sdcard/test.txt"

Response 200:
{
  "success": true,
  "dest": "/sdcard/test.txt",
  "bytes_written": 1024
}
```

### 3.7 文件拉取（Pull）

```
GET /devices/{device_id}/pull?path=/sdcard/test.txt

Response 200:
Content-Type: application/octet-stream
Content-Disposition: attachment; filename="test.txt"
Body: <file binary>
```

### 3.8 输入操作

所有输入操作统一通过 `POST /devices/{device_id}/input` 接口，通过 `action` 字段区分。

#### 单击（Tap）
```
POST /devices/{device_id}/input

{
  "action": "tap",
  "x": 540,
  "y": 960
}

Response 200:
{ "success": true, "action": "tap", "elapsed_ms": 42 }
```

#### 双击（Double Tap）
```
{
  "action": "double_tap",
  "x": 540,
  "y": 960,
  "interval_ms": 100      // 两次点击间隔，默认 100ms
}
```

#### 长按（Long Press）
```
{
  "action": "long_press",
  "x": 540,
  "y": 960,
  "duration_ms": 1000     // 按压时长，默认 1000ms
}
```

#### 滑动（Swipe）
```
{
  "action": "swipe",
  "from_x": 540,
  "from_y": 1600,
  "to_x": 540,
  "to_y": 400,
  "duration_ms": 300      // 滑动时长，默认 300ms
}
```
常用滑动场景：

| 场景 | from | to | duration |
|------|------|----|----------|
| 下拉通知栏 | (540, 0) | (540, 800) | 300 |
| 上滑返回桌面 | (540, 1800) | (540, 400) | 400 |
| 左滑翻页 | (900, 960) | (180, 960) | 300 |
| 右滑翻页 | (180, 960) | (900, 960) | 300 |

#### 按键（Key）
```
{
  "action": "key",
  "key": "home"           // 字符串别名 或 Android KeyEvent 整数值
}
```

常用 key 别名：`home` / `back` / `menu` / `power` / `volume_up` / `volume_down` / `recent` / `enter` / `delete`

#### 文本输入（Text）—— 支持中英文
```
{
  "action": "text",
  "text": "Hello 你好世界",
  "method": "auto"   // auto(默认) | ascii | clipboard
}
```
`method: "auto"` 时，App 自动检测是否含非 ASCII 字符：含则走剪贴板路径，否则走 `input text` 快速路径。

#### 剪贴板写入
```
{ "action": "clipboard_set", "text": "要写入的内容" }
```

#### 剪贴板读取
```
{ "action": "clipboard_get" }

Response 200:
{ "success": true, "text": "当前剪贴板内容", "elapsed_ms": 12 }
```

#### 复制（Copy）
```
{ "action": "copy" }

Response 200:
{ "success": true, "text": "被复制的文本", "elapsed_ms": 85 }
```
触发 `KEYCODE_COPY`，然后读取剪贴板返回内容。

#### 粘贴（Paste）
```
{ "action": "paste" }
```
触发 `KEYCODE_PASTE`，将当前剪贴板内容粘贴到焦点控件。

#### 批量操作（Actions）

支持一次请求执行多个操作，按顺序串行执行，每步可指定 `delay_ms` 间隔：
```
POST /devices/{device_id}/input/batch

{
  "actions": [
    { "action": "tap",    "x": 540, "y": 960 },
    { "delay_ms": 500 },
    { "action": "text",   "text": "username" },
    { "action": "key",    "key": "tab" },
    { "action": "text",   "text": "password" },
    { "action": "key",    "key": "enter" }
  ]
}

Response 200:
{
  "success": true,
  "total_steps": 6,
  "elapsed_ms": 1234,
  "results": [
    { "step": 0, "action": "tap",   "success": true, "elapsed_ms": 45 },
    { "step": 1, "action": "delay", "success": true, "elapsed_ms": 500 },
    ...
  ]
}
```

### 3.9 Logcat 流（SSE）

```
GET /devices/{device_id}/logcat?filter=*:W&format=brief
Accept: text/event-stream

Response 200 (SSE stream):
data: W/Tag(12345): message\n\n
data: W/Tag(12346): message\n\n
...

停止：客户端断开 TCP 连接即可
```

### 3.10 属性查询

```
GET /devices/{device_id}/props/{prop_name}
例: GET /devices/{id}/props/ro.build.version.release

Response 200:
{
  "prop": "ro.build.version.release",
  "value": "13"
}
```

### 3.11 健康检查

```
GET /health

Response 200:
{
  "status": "ok",
  "online_devices": 3,
  "uptime_seconds": 3600
}
```

---

## 四、错误码一览

| HTTP Code | error 字段 | 说明 |
|-----------|-----------|------|
| 400 | `invalid_request` | 请求参数格式错误 |
| 401 | `unauthorized` | Token 缺失或无效 |
| 404 | `device_not_found` | 设备不存在或从未注册 |
| 408 | `command_timeout` | 命令执行超时 |
| 413 | `file_too_large` | 上传文件超过限制（默认 500MB） |
| 422 | `exec_failed` | 命令执行失败（exit_code != 0 不算） |
| 503 | `device_offline` | 设备已断线 |
| 500 | `internal_error` | 服务端内部错误 |

---

## 五、WebSocket 连接握手

### 设备连接端点
```
GET /ws/device
Upgrade: websocket
Connection: Upgrade
Authorization: Bearer <device_token>
X-Device-ID: 550e8400-...
X-App-Version: 1.0.0
```

- Server 验证 Token 后返回 `101 Switching Protocols`
- Server 验证失败返回 `401 Unauthorized`（不升级连接）

### 心跳超时规则
- App 每 **30 秒** 发送 PING 帧
- Server 必须在 **10 秒**内回 PONG
- Server 侧若 **90 秒**未收到 PING，主动 close(1001, "ping_timeout")

---

## 六、协议版本协商

REGISTER 帧中携带 `app_ver`，Server 在 REGISTER_ACK 中返回 `protocol_ver`：

```json
{
  "status": "ok",
  "protocol_ver": "1",
  "features": ["shell", "screencap", "install", "logcat", "push", "pull"]
}
```

App 根据 `features` 列表决定 UI 展示哪些功能，实现向后兼容。
