# ADBTunnel — 安全设计

## 一、威胁模型

ADBTunnel 将 Android 设备的 Shell 执行能力暴露到网络，属于高权限操作。安全设计聚焦三个核心目标：**认证（谁能用）、加密（传输安全）、限速（防滥用）**。

### 主要威胁面

| 威胁 | 描述 | 风险等级 |
|------|------|----------|
| 未授权调用 | 攻击者直接调用 HTTP API 控制设备 | 高 |
| 传输被拦截 | 明文 WS/HTTP 被中间人监听或篡改 | 高 |
| Token 泄露 | Token 被从 App 或日志中提取 | 高 |
| 命令注入 | 构造特殊 shell 参数逃逸 | 中 |
| 暴力破解 | 穷举 Token | 中 |
| 拒绝服务 | 大量并发请求压垮 Server | 中 |

---

## 二、认证机制

### 单 Token 设计

系统使用**一个 Token** 同时用于设备注册和 API 调用，配置简单，适合个人和小团队使用。

```
设备注册（WebSocket 握手 Header）:
  Authorization: Bearer <token>

HTTP API 调用:
  Authorization: Bearer <token>
```

Token 由部署者在服务器配置，通过安全渠道（手动配置、二维码扫码等）同步给 Android App。

### Token 生成

```bash
openssl rand -hex 32
# 示例: 4f3c2a1b8e9d... (64 个十六进制字符 = 256 bit 熵)
```

最低长度：**32 字节（256 bit）**，禁止使用可猜测的字符串（如 `123456`、`password`）。

### 验证失败处理

- WebSocket：握手时返回 `401`，不升级连接；
- HTTP：返回 `{"error": "unauthorized"}`；
- 不返回任何关于 Token 格式的提示。

---

## 三、传输加密（TLS）

**生产环境强制 TLS**，明文仅允许本地开发（`localhost`）。

| 连接 | 要求 |
|------|------|
| 设备 → 服务器（WS） | `wss://`（TLS 1.2+） |
| 调用方 → 服务器（HTTP） | `https://`（TLS 1.2+） |

推荐在 Nginx 做 TLS 终止（参见部署指南），服务器本身监听明文 8080 端口。

Android App 默认信任系统 CA 证书。使用自签名证书时，通过 `network_security_config.xml` 添加信任锚：

```xml
<!-- res/xml/network_security_config.xml -->
<network-security-config>
    <domain-config>
        <domain includeSubdomains="true">your-server.example.com</domain>
        <trust-anchors>
            <certificates src="@raw/server_cert" />
        </trust-anchors>
    </domain-config>
</network-security-config>
```

---

## 四、Token 安全存储

### Android App 侧

使用 Jetpack Security 的 `EncryptedSharedPreferences`，Token 存储在 Android Keystore 加密空间：

```java
MasterKey masterKey = new MasterKey.Builder(context)
    .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
    .build();

SharedPreferences securePrefs = EncryptedSharedPreferences.create(
    context, "adbtunnel_secure", masterKey,
    EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
    EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
);
```

Token 不输出到 Logcat，不写入普通 SharedPreferences。

### Server 侧

通过环境变量注入，不硬编码在配置文件中，配置文件加入 `.gitignore`：

```bash
export ADBTUNNEL_TOKEN=$(openssl rand -hex 32)
./adbtunnel-server
```

---

## 五、防滥用

### 命令黑名单

对 `shell` 命令的 `args` 字段过滤高危模式：

```rust
const BLOCKED_PATTERNS: &[&str] = &[
    "rm -rf /",
    ":(){ :|:& };:",  // Fork bomb
    "dd if=/dev/",
    "mkfs",
];
```

### 限速

```toml
# config/default.toml
[limits]
rate_per_ip      = 20   # 每 IP 每秒最大请求数
burst            = 40   # 突发上限
command_timeout  = 30   # 默认命令超时（秒）
```

### 心跳超时

90 秒未收到 App PING，Server 主动断开连接，防止僵尸会话占用资源。

---

## 六、部署检查清单

- [ ] 生产环境使用 `wss://` + `https://`（Nginx TLS）
- [ ] Token 使用 `openssl rand -hex 32` 生成
- [ ] Token 通过环境变量注入，不提交到代码库
- [ ] Android App 使用 `EncryptedSharedPreferences` 存储 Token
- [ ] 配置命令黑名单
- [ ] 启用限速（Nginx + Tower 双层）
- [ ] Server 以非 root 用户运行
- [ ] 定期轮换 Token（建议每 90 天或人员变动时）
