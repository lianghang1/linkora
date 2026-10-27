# 配置参考（Configuration）

> 0.6.0 全量配置项。所有键都以 `linkora.` 开头；Boot 宿主写 application.yml，
> MVC 宿主用系统属性 `-D<key>=<value>` 或属性文件（键完全相同）。

## 一、配置速查表

### 服务端（`linkora.server.*`）

| 键 | 默认值 | 说明 |
|---|---|---|
| `enabled` | `true` | 服务端装配总开关 |
| `store` | `sqlite` | 存储模式：`sqlite`（内嵌文件，零配置）/ `datasource`（复用宿主库，与业务同事务） |
| `db-path` | `./linkora/linkora-server.db` | sqlite 模式的库文件位置（父目录自动创建） |
| `auto-migrate` | `true` | 启动时自动建表/迁移（幂等） |
| `auth-mode` | `hmac` | 认证：`token`（回调宿主 TokenValidator）/ `hmac`（签名凭证）/ `anonymous`（放行，仅开发） |
| `register-clients` | 空 | 启动时自动注册客户端：`客户端ID: true/false`（幂等） |
| `allow-anonymous` | `false` | 无认证器时的回退放行（hmac 模式演示用；生产必配认证） |
| `max-delivery-retries` | SDK 默认 | 投递重试次数，耗尽进死信 |
| `retry-backoff-base-ms` / `-max-ms` | SDK 默认 | 重试退避区间 |
| `sweep-interval-ms` | SDK 默认 | 后台扫描周期（补投/死信/清理） |
| `push-batch-size` / `push-pacing-*` | SDK 默认 | 推送批量与节奏 |
| `message-size-limit-bytes` | SDK 默认 | 单条消息大小上限 |
| `retention-days` | 7 | 终结消息保留天数 |
| `events-path` / `messages-path` / `acks-path` | `/events` `/messages` `/acks` | 三个端点路径 |
| `alert-webhook-url` | 空（关闭） | 死信 Webhook 告警地址（企业微信/钉钉/飞书/raw） |
| `alert-webhook-type` | `raw` | 告警报文格式 |
| `inbox-stuck-minutes` | 30 | 收件箱"卡死"判定阈值 |

### 客户端（`linkora.client.*`）

| 键 | 默认值 | 说明 |
|---|---|---|
| `enabled` | `true` | 引包即装配（0.6.0 起；属性保留作显式关闭） |
| `server-url` | 必填 | 对端服务端地址 |
| `client-id` | 必填 | 本端标识（服务端 register-clients 或控制台注册） |
| `store` | `sqlite` | 与服务端对等：`sqlite` / `datasource`（宿主库同事务 outbox） |
| `db-path` | `./linkora/linkora-client.db` | sqlite 模式库文件（快照表也在这里） |
| `auth-mode` | `hmac` | `token` / `hmac` / `none`（匿名） |
| `auth-token` | 空 | token 模式的静态 token（动态见下方 TokenProvider） |
| `sender-interval-ms` 等 | SDK 默认 | 发送/恢复调度周期 |

### 传输加密（根级 `linkora.*`，两端必须一致）

| 键 | 默认值 | 说明 |
|---|---|---|
| `payload-cipher` | `true` | 传输加密开关（AES-256-GCM，与认证方式无关） |
| `payload-cipher-key` | **必配** | 32 字节密钥（`openssl rand -base64 32`）；缺失启动即失败 |

### 数据同步（`sync.*`，客户端可选）

| 键 | 默认值 | 说明 |
|---|---|---|
| `interval-ms` | `30000` | 定时同步周期（全量读 + 快照 diff + 只发增量） |

### 控制台（`linkora.admin-console.*`，可选 jar）

| 键 | 默认值 | 说明 |
|---|---|---|
| `enabled` | `false` | 控制台装配开关 |
| `token` | **必配** | 控制台访问令牌（Bearer） |

## 二、三个典型场景的完整配置

### 场景 A：本地开发/演示（零外部依赖）

```yaml
linkora:
  server:
    auth-mode: anonymous          # 开发放行（生产禁用）
    allow-anonymous: true
  payload-cipher-key: "dev-key"
```

### 场景 B：生产（宿主 MySQL + token 认证）

```yaml
spring:
  datasource:
    url: jdbc:mysql://内网/db?...
linkora:
  server:
    store: datasource             # 消息与业务同库，publish 与业务同事务
    auth-mode: token              # TokenValidator 回调宿主登录体系
  payload-cipher-key: "${LINKORA_CIPHER_KEY}"   # 从环境变量/配置中心注入
```

### 场景 C：边缘设备客户端（离线优先）

```yaml
linkora:
  client:
    server-url: http://总部:8082
    client-id: kiosk-001
    auth-mode: token
  payload-cipher-key: "${LINKORA_CIPHER_KEY}"
# 默认 sqlite 本地队列：断网期间消息本地缓冲，重连自动补发
```

## 三、配置的加载方式

| 宿主 | 方式 |
|---|---|
| Spring Boot | application.yml / 环境变量 / 配置中心（Nacos 等原样生效） |
| Spring MVC | 系统属性 `-D<key>=<value>` 或宿主属性文件（Environment 统一读取） |

密钥类配置建议走配置中心/环境变量，不要提交到代码库（见 security.md 的密钥管理）。
