# ADBTunnel — 部署指南

## 一、环境要求

### Server 编译环境
- Rust 1.75+（`rustup` 安装）
- Linux / macOS / Windows（生产推荐 Linux）
- 若启用内置 TLS：`openssl` 开发库

### Android 编译环境
- Android Studio Hedgehog (2023.1) 或更高
- JDK 17+
- Android SDK API 24~34
- Gradle 8.x

### 服务器运行环境
- Linux（推荐 Ubuntu 22.04 / Debian 12）
- 开放端口：`8080`（HTTP/WS，若不用 Nginx） 或 `443`（HTTPS，配合 Nginx）
- 可选：Docker 20.10+

---

## 二、编译 Server

### 2.1 克隆并编译

```bash
git clone https://github.com/your-org/adbtunnel.git
cd adbtunnel/server

# Debug 构建
cargo build

# Release 构建（生产）
cargo build --release

# 产物路径
ls target/release/adbtunnel-server
```

### 2.2 运行（最小配置）

```bash
# 生成 Token
export ADBTUNNEL_TOKEN=$(openssl rand -hex 32)
export ADBTUNNEL_SERVER_PORT=8080
export RUST_LOG=info

./target/release/adbtunnel-server
```

### 2.3 配置文件方式

```bash
cp config/default.toml config/production.toml
# 编辑 config/production.toml，填写 token 等参数
APP_ENV=production ./target/release/adbtunnel-server
```

---

## 三、编译 Android App

### 3.1 通过 Android Studio

1. 用 Android Studio 打开 `android/` 目录；
2. 在 `app/src/main/res/raw/` 中放置服务器证书（如使用自签名 TLS）；
3. Build → Generate Signed Bundle/APK → APK；
4. 安装到设备：
   ```bash
   adb install app-release.apk
   ```

### 3.2 命令行编译

```bash
cd android/
./gradlew assembleRelease

# 产物路径
ls app/build/outputs/apk/release/app-release.apk
```

---

## 四、Nginx 配置（推荐生产方案）

### 4.1 安装 Certbot 获取 TLS 证书

```bash
sudo apt install certbot python3-certbot-nginx
sudo certbot --nginx -d your-server.example.com
```

### 4.2 Nginx 配置文件

```nginx
# /etc/nginx/sites-available/adbtunnel
upstream adbtunnel_backend {
    server 127.0.0.1:8080;
}

# HTTP → HTTPS 重定向
server {
    listen 80;
    server_name your-server.example.com;
    return 301 https://$host$request_uri;
}

server {
    listen 443 ssl http2;
    server_name your-server.example.com;

    ssl_certificate /etc/letsencrypt/live/your-server.example.com/fullchain.pem;
    ssl_certificate_key /etc/letsencrypt/live/your-server.example.com/privkey.pem;
    ssl_protocols TLSv1.2 TLSv1.3;
    ssl_ciphers ECDHE-RSA-AES128-GCM-SHA256:ECDHE-RSA-AES256-GCM-SHA384;

    # 限速
    limit_req_zone $binary_remote_addr zone=api_limit:10m rate=20r/s;

    # WebSocket 设备连接（长连接）
    location /ws/ {
        proxy_pass http://adbtunnel_backend;
        proxy_http_version 1.1;
        proxy_set_header Upgrade $http_upgrade;
        proxy_set_header Connection "Upgrade";
        proxy_set_header Host $host;
        proxy_set_header X-Real-IP $remote_addr;
        proxy_read_timeout 86400s;
        proxy_send_timeout 86400s;
    }

    # HTTP API
    location /api/ {
        limit_req zone=api_limit burst=40 nodelay;
        proxy_pass http://adbtunnel_backend;
        proxy_set_header Host $host;
        proxy_set_header X-Real-IP $remote_addr;
        # SSE 流不缓冲
        proxy_buffering off;
        proxy_cache off;
        proxy_read_timeout 300s;
        chunked_transfer_encoding on;
    }

    # 健康检查（不限速）
    location /health {
        proxy_pass http://adbtunnel_backend;
    }
}
```

```bash
sudo ln -s /etc/nginx/sites-available/adbtunnel /etc/nginx/sites-enabled/
sudo nginx -t && sudo systemctl reload nginx
```

---

## 五、Systemd 服务（Linux 生产）

### 5.1 创建服务用户

```bash
sudo useradd -r -s /bin/false adbtunnel
sudo mkdir -p /opt/adbtunnel
sudo cp target/release/adbtunnel-server /opt/adbtunnel/
sudo cp -r config/ /opt/adbtunnel/
sudo chown -R adbtunnel:adbtunnel /opt/adbtunnel
```

### 5.2 创建 Systemd 单元文件

```ini
# /etc/systemd/system/adbtunnel.service
[Unit]
Description=ADBTunnel Server
After=network.target
Wants=network-online.target

[Service]
Type=simple
User=adbtunnel
Group=adbtunnel
WorkingDirectory=/opt/adbtunnel

# Token 敏感信息通过 EnvironmentFile 注入
EnvironmentFile=/etc/adbtunnel/secrets.env
Environment=RUST_LOG=info
Environment=APP_ENV=production

ExecStart=/opt/adbtunnel/adbtunnel-server
Restart=always
RestartSec=5
StandardOutput=journal
StandardError=journal

# 安全加固
NoNewPrivileges=true
PrivateTmp=true
ProtectSystem=strict
ProtectHome=true

[Install]
WantedBy=multi-user.target
```

```bash
# 创建 secrets 文件（权限严格控制）
sudo mkdir -p /etc/adbtunnel
sudo tee /etc/adbtunnel/secrets.env << EOF
ADBTUNNEL_TOKEN=$(openssl rand -hex 32)
EOF
sudo chmod 600 /etc/adbtunnel/secrets.env
sudo chown adbtunnel:adbtunnel /etc/adbtunnel/secrets.env

# 启用并启动服务
sudo systemctl daemon-reload
sudo systemctl enable adbtunnel
sudo systemctl start adbtunnel
sudo systemctl status adbtunnel
```

---

## 六、Docker 部署

### 6.1 Dockerfile（Server）

```dockerfile
# 多阶段构建
FROM rust:1.75-slim as builder
WORKDIR /app
COPY Cargo.toml Cargo.lock ./
COPY src ./src
RUN cargo build --release

FROM debian:bookworm-slim
RUN apt-get update && apt-get install -y ca-certificates && rm -rf /var/lib/apt/lists/*
COPY --from=builder /app/target/release/adbtunnel-server /usr/local/bin/
COPY config/default.toml /etc/adbtunnel/config.toml
EXPOSE 8080
USER nobody
CMD ["adbtunnel-server"]
```

### 6.2 docker-compose.yml

```yaml
version: "3.9"

services:
  adbtunnel:
    build: ./server
    restart: unless-stopped
    ports:
      - "8080:8080"
    environment:
      - ADBTUNNEL_TOKEN=${TOKEN}
      - RUST_LOG=info
    volumes:
      - ./config/production.toml:/etc/adbtunnel/config.toml:ro
    healthcheck:
      test: ["CMD", "curl", "-f", "http://localhost:8080/health"]
      interval: 30s
      timeout: 5s
      retries: 3

  nginx:
    image: nginx:alpine
    restart: unless-stopped
    ports:
      - "80:80"
      - "443:443"
    volumes:
      - ./nginx/adbtunnel.conf:/etc/nginx/conf.d/adbtunnel.conf:ro
      - /etc/letsencrypt:/etc/letsencrypt:ro
    depends_on:
      - adbtunnel
```

```bash
# 启动
cp .env.example .env   # 填写 TOKEN
docker-compose up -d

# 查看日志
docker-compose logs -f adbtunnel
```

---

## 七、Android App 配置

App 首次启动会弹出配置界面，需要填写：

| 配置项 | 说明 | 示例 |
|--------|------|------|
| 服务器地址 | WebSocket 服务器 URL | `wss://your-server.example.com` |
| Device Token | 设备注册 Token | `4f3c2a1b...` (32字节hex) |

配置保存后，App 会立即尝试连接。连接成功后通知栏显示"已连接"。

---

## 八、验证部署

### 8.1 检查服务器健康状态

```bash
curl https://your-server.example.com/health
# 预期: {"status":"ok","online_devices":0,"uptime_seconds":...}
```

### 8.2 查看在线设备

```bash
curl -H "Authorization: Bearer <api_token>" \
  https://your-server.example.com/api/v1/devices
```

### 8.3 执行测试命令

```bash
DEVICE_ID="550e8400-..."
API_TOKEN="your-api-token"
SERVER="https://your-server.example.com"

curl -X POST \
  -H "Authorization: Bearer ${API_TOKEN}" \
  -H "Content-Type: application/json" \
  -d '{"command": "echo hello from device"}' \
  "${SERVER}/api/v1/devices/${DEVICE_ID}/shell"

# 预期:
# {
#   "exit_code": 0,
#   "stdout": "hello from device\n",
#   "stderr": "",
#   "elapsed_ms": 156
# }
```

### 8.4 截图测试

```bash
curl -H "Authorization: Bearer ${API_TOKEN}" \
  "${SERVER}/api/v1/devices/${DEVICE_ID}/screenshot" \
  -o screenshot.png
```

---

## 九、日志查看

```bash
# Systemd 部署
sudo journalctl -u adbtunnel -f

# Docker 部署
docker-compose logs -f adbtunnel

# 日志级别调整（临时）
sudo systemctl stop adbtunnel
export RUST_LOG=debug
sudo systemctl start adbtunnel
```
