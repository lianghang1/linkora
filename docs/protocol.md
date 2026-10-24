# 线路协议设计

> 逻辑前缀统一为 `/linkora`。协议版本：`Protocol.PROTOCOL_VERSION = 1`，
> 通过请求头 `X-Linkora-Protocol` 携带；主版本不兼容，次版本向后兼容。

## 消息信封

所有线路上的可靠消息共用统一信封（对应 `LinkoraMessage`）：

| 字段 | 类型 | 必填 | 说明 |
|---|---|---|---|
| messageId | string | 是 | 全局唯一消息 ID，重试不得变更 |
| clientId | string | 是 | 上行=来源客户端；下行=目标客户端（服务端以认证上下文校验） |
| messageType | string | 是 | 业务消息类型，路由键 |
| payload | bytes(string) | 是 | 序列化后的业务负载（默认 JSON） |
| createdAt | int64 | 是 | 创建时间，epoch 毫秒 |
| expiresAt | int64 | 否 | 过期时间，epoch 毫秒 |
| schemaVersion | int32 | 是 | 负载结构版本，默认 1 |
| traceId | string | 否 | 链路追踪 ID |
| sequenceKey | string | 否 | 顺序键（≤128 字符；null/空白 = 无键，不参与顺序约束） |

## HTTP 接口

三个核心端点由框架提供（`LinkoraServlets` 统一注册 / starter 自动注册），路径前缀可配：

| 接口 | 方法 | 说明 | 幂等性 |
|---|---|---|---|
| `/events` | GET (SSE) | 下行事件流；Header 携带凭据与 `Last-Event-ID` | 连接级 |
| `/messages` | POST | 客户端上行消息 | messageId 唯一约束去重 |
| `/acks` | POST | 统一 ACK（RECEIVED / PROCESSED / FAILED） | 幂等，支持重复提交 |

监控与人工处置不在线路协议内，而是 `LinkoraServer` 的管理方法
（可由宿主选择性透出为 HTTP），见 [api.md](api.md)。

错误响应统一结构：`{"errorCode": "AUTH_FAILED", "message": "...", "messageId": "..."}`。
错误码见 `LinkoraErrorCode`；错误响应不泄露凭据、堆栈与敏感数据。

## SSE 事件格式

```
event: msg
id: <投递 seq，即 Last-Event-ID>
data: <消息信封 JSON>

event: hb
data: <epoch 毫秒>

event: ctrl
data: <管理指令 JSON（{"cmd":...,"params":{...}}，不落库不补发）>
```

- 业务事件类型为 `msg`；`id` 为服务端 per-client 递增投递 seq，客户端可凭
  跳号检测消息空洞。
- 心跳事件类型为 `hb`，间隔可配置（默认 15s）；客户端读空闲超时（默认 45s，约为心跳间隔 3 倍）触发重连。
- 管理指令事件类型为 `ctrl`（白名单见 `com.linkora.core.admin.ClientCommands`），
  仅对在线客户端实时生效，不参与 `Last-Event-ID` 补发。
- 断线恢复依据是持久化投递记录与 ACK 状态，`Last-Event-ID` 仅是辅助加速手段。

## ACK 语义

| 类型 | 触发时机 | 语义边界 |
|---|---|---|
| RECEIVED | 客户端 SQLite 事务提交成功后 | 仅代表已持久化，不代表业务处理成功 |
| PROCESSED | 业务监听器成功返回后 | 业务处理成功 |
| FAILED | 业务监听器抛异常/显式失败 | 附带错误码；消息保留并按策略重试 |

## 认证方式

- 所有接口要求 Header 认证（凭据禁止出现在 URL）。
- 高熵随机 token（≥32 字节）；写请求附加 HMAC 签名（时间戳 + nonce + 共享密钥）。
- 详见 `security.md`。
