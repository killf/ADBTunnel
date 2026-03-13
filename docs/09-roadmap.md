# ADBTunnel — 开发路线图

## 里程碑概览

```
M1 (基础可用)     M2 (生产稳定)     M3 (功能完善)     M4 (平台化)
     │                 │                 │                 │
─────┼─────────────────┼─────────────────┼─────────────────┼────►
   Week 1-4         Week 5-8         Week 9-14        Week 15-20
```

---

## M1 — 基础可用（Week 1~4）

**目标**：跑通完整链路，实现 Shell 命令和截图。

### Server（Rust）
- [ ] 项目骨架：Cargo 工程结构、配置加载、日志
- [ ] WebSocket 服务端：设备注册、心跳、断线清理
- [ ] SessionManager：设备会话管理
- [ ] Dispatcher：命令调度（含超时）
- [ ] HTTP API：`/shell`、`/screenshot`、`/health`、`/devices`
- [ ] Token 认证中间件
- [ ] 基础错误处理和 JSON 响应格式

### Android App（Java）
- [ ] 项目骨架：模块结构、build.gradle
- [ ] AdbStateChecker：检测 ADB/开发者模式
- [ ] SetupActivity：引导页（未开启时引导用户操作）
- [ ] DeviceRegistry：device_id 生成与持久化
- [ ] WsClient：基于 OkHttp 的 WebSocket 封装
- [ ] FrameParser：帧编解码
- [ ] TunnelForegroundService：前台服务、WS 连接维护
- [ ] ShellExecutor：Shell 命令执行
- [ ] ScreencapExecutor：截图并回传
- [ ] 断线重连（指数退避）

### 验收标准
```
✓ App 安装后能检测 ADB 状态并引导开启
✓ App 启动后成功注册到 Server
✓ curl POST /shell 能在设备上执行命令并返回结果
✓ curl GET /screenshot 能返回 PNG 图片
✓ Server 重启后 App 自动重连
```

---

## M2 — 生产稳定（Week 5~8）

**目标**：完善可靠性、安全性，支持 APK 安装和文件传输。

### Server
- [ ] 文件上传处理（multipart）与分块 WS 转发
- [ ] APK 安装 API：`/install`
- [ ] 文件推拉 API：`/push`、`/pull`
- [ ] Logcat 流式 API（SSE）
- [ ] 命令黑名单过滤
- [ ] IP 限速中间件（Tower）
- [ ] Prometheus 指标暴露（`/metrics`）
- [ ] 结构化审计日志（JSON 格式）
- [ ] Docker 镜像 + docker-compose 文件
- [ ] Systemd 单元文件

### Android App
- [ ] InstallExecutor：APK 接收、安装、清理
- [ ] FileTransferExecutor：push/pull 分块收发
- [ ] LogcatExecutor：流式 logcat 输出
- [ ] EncryptedSharedPreferences 存储 Token
- [ ] BootCompletedReceiver：开机自启
- [ ] ConfigActivity：服务器地址 + Token 配置页
- [ ] 通知更新：连接状态实时同步
- [ ] 前台服务类型适配 Android 14+

### 验收标准
```
✓ APK 安装成功（含失败场景返回具体原因）
✓ 文件 push/pull 500MB 以内稳定传输
✓ Logcat SSE 流客户端断开后设备侧进程正确终止
✓ Token 存储在加密 Keystore 中
✓ Docker 一键部署可用
✓ Prometheus 指标正常暴露
```

---

## M3 — 功能完善（Week 9~14）

**目标**：覆盖更多 ADB 功能，提升大规模设备管理能力。

### 功能扩展
- [ ] `dumpsys` 命令支持（获取系统诊断信息）
- [ ] `getprop` / `setprop` 批量接口
- [ ] 屏幕录制（screenrecord → 流式回传）
- [ ] APK 卸载 API：`DELETE /packages/{pkg}`
- [ ] 包列表查询：`GET /packages`
- [ ] 设备属性查询：`GET /props/{key}`

### 可靠性
- [ ] 命令级别的超时独立配置（per-request timeout）
- [ ] 大文件分块传输的断点续传（chunked resume）
- [ ] WebSocket 帧级别的 CRC 校验（防止网络损坏）
- [ ] 设备离线时 Server 端 pending 请求的优雅清理

### 可观测性
- [ ] 链路追踪（OpenTelemetry SDK 集成，可选）
- [ ] 命令执行统计（P50/P95/P99 延迟）
- [ ] 设备在线率统计

### 验收标准
```
✓ 同时管理 50 台设备，各设备并发 5 条命令，无串扰
✓ 网络抖动（30% 丢包）下重连稳定，命令不丢失
✓ P95 Shell 命令端到端延迟 < 500ms（LAN 环境）
```

---

## M4 — 平台化（Week 15~20）

**目标**：面向团队/企业用户，提供多租户、Web 控制台、SDK。

### 多租户
- [ ] 用户/团队概念（DB 层：PostgreSQL）
- [ ] 设备与用户绑定（JWT 认证替代静态 Token）
- [ ] 设备分组管理
- [ ] 权限模型：只读/执行/管理

### Web 控制台（前端）
- [ ] 设备列表与状态总览
- [ ] 在线终端（WebSocket + xterm.js）
- [ ] 截图预览
- [ ] APK 拖拽安装
- [ ] 操作历史日志查询

### SDK / 客户端库
- [ ] Python SDK：`pip install adbtunnel`
- [ ] HTTP API 文档（OpenAPI 3.0 / Swagger UI）

### 验收标准
```
✓ Web 控制台能对设备执行交互式 shell
✓ Python SDK 能在 3 行代码内完成截图
✓ OpenAPI 文档自动生成并可在线调用
```

---

## 技术风险与应对

| 风险 | 概率 | 影响 | 应对措施 |
|------|------|------|----------|
| Android 厂商后台限制杀死 Service | 高 | 高 | 引导用户加入电池白名单；双重保活（JobScheduler + Foreground） |
| Android 版本碎片化（API 差异） | 中 | 中 | 最低支持 API 24，分版本适配关键 API |
| WebSocket 长连接在部分运营商网络断开 | 中 | 中 | 30s Ping/Pong 心跳；客户端主动断线检测 |
| 大文件传输内存溢出（OOM） | 低 | 高 | 流式分块（64KB/块），不全量加载到内存 |
| Server 单点故障 | 低 | 高 | M4 阶段：Redis 会话共享 + 多节点部署 |
| Token 泄露导致设备被控 | 低 | 极高 | Token 轮换机制；审计日志；异常告警 |

---

## 版本规划

| 版本 | 对应里程碑 | 主要特性 |
|------|-----------|----------|
| v0.1.0 | M1 完成 | Shell + 截图，内部测试 |
| v0.2.0 | M2 完成 | 文件传输 + APK 安装，生产可用 |
| v0.3.0 | M3 完成 | 完整 ADB 功能覆盖 |
| v1.0.0 | M4 完成 | Web 控制台 + SDK，正式发布 |
