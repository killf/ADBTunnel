# ADBTunnel

通过 WebSocket 隧道将 Android 设备的 ADB 能力暴露到远程服务器，实现随时随地的 ADB 远程连接。

## 核心功能

- **Android App**（Java）：检测 ADB 状态、引导开启、维护隧道连接
- **服务端**（Rust）：接受设备注册，通过 TCP 端口暴露 ADB 代理
- **隧道协议**：WebSocket 多路复用，支持多并发 ADB 会话

## 使用方式

```bash
# 1. 在目标 Android 设备上安装并配置 ADBTunnel App
# 2. 设备上线后，通过标准 ADB 连接
adb connect your-server.com:15555
adb devices
adb shell
```

## 文档

| 文档 | 说明 |
|------|------|
| [产品概述](docs/01-product-overview.md) | 项目背景、价值与技术选型 |
| [系统架构](docs/02-system-architecture.md) | 整体架构、模块说明与数据流 |
| [执行流程](docs/03-execution-flow.md) | App 启动、隧道建立、ADB 会话完整流程 |
| [通信协议](docs/04-protocol-design.md) | WebSocket 帧格式与 HTTP API 设计 |
| [Android 设计](docs/05-android-design.md) | Java 实现方案与关键类设计 |
| [Server 设计](docs/06-server-design.md) | Rust 实现方案与核心代码结构 |
| [安全设计](docs/07-security-design.md) | 认证、加密、访问控制方案 |
| [部署指南](docs/08-deployment-guide.md) | 编译、配置、运行与 Docker 部署 |
| [开发路线图](docs/09-roadmap.md) | 里程碑规划与技术风险 |

## 技术栈

- **Android App**: Java, OkHttp (WebSocket), Android Foreground Service
- **Server**: Rust, Tokio, Axum, tokio-tungstenite
- **协议**: WebSocket (WSS), 自定义二进制多路复用帧
