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
| orderingKey | string | 否 | 顺序键（≤128）。同 clientId 下同键消息按 seq 单调投递；缺省表示不参与顺序约束 |

## 接口清单（阶段 3 逐步实现）

| 接口 | 方法 | 说明 | 幂等性 |
|---|---|---|---|
| `/linkora/stream` | GET (SSE) | 事件流；Header 携带凭据与 `Last-Event-ID` | 连接级 |
| `/linkora/messages` | POST | 客户端上行消息 | messageId 唯一约束去重 |
| `/linkora/acks` | POST | 统一 ACK（RECEIVED / PROCESSED / FAILED） | 幂等，支持重复提交 |
| `/linkora/pending` | GET | 查询待补发消息（重连恢复） | 只读 |
| `/linkora/clients/{id}/status` | GET | 客户端连接与积压状态 | 只读 |
| `/linkora/messages/{id}` | GET | 消息投递状态查询 | 只读 |
| `/linkora/health` | GET | 健康检查 | 只读 |
| `/linkora/admin/messages/{id}/retry` | POST | 人工重试（死信复活） | 幂等 |

错误响应统一结构：`{"errorCode": "AUTH_FAILED", "message": "...", "messageId": "..."}`。
错误码见 `LinkoraErrorCode`；错误响应不泄露凭据、堆栈与敏感数据。

## SSE 事件格式

```
event: message
id: <投递 seq，即 Last-Event-ID>
data: <消息信封 JSON>

event: heartbeat
data: <epoch 毫秒>
```

- `id` 为服务端 per-client 递增投递 seq；客户端可凭跳号检测消息空洞。
- 心跳间隔可配置（默认 15s）；客户端心跳超时（默认 2 倍间隔）触发重连。
- 恢复依据是持久化投递记录与 ACK 状态，`Last-Event-ID` 仅是辅助加速手段。

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
