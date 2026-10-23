# 可靠性设计

## 基本语义

- **至少一次投递（at-least-once）**：允许重复投递，不承诺底层只发送一次。
- **不承诺绝对零丢失**：SQLite 与业务数据库是两个独立事务；业务提交后、SDK
  事件落盘前崩溃的窗口由业务集成规范 + 对账 + 可选 Outbox 兜底。
- 每条概念不得混淆：SSE 写出成功 ≠ 客户端收到 ≠ 已落 SQLite ≠ 业务处理成功 ≠ 服务端收到 ACK。

## 状态机

### 服务端投递记录（MessageState）

```
CREATED → ACCEPTED → RECEIVED → PROCESSED(终态)
                   └→ RECEIVED → FAILED → RETRYING → PROCESSED / FAILED(循环)
任意非终态 → EXPIRED(终态) / DEAD_LETTER
DEAD_LETTER → RETRYING（仅人工重试）
```

规则：只能前进不能回退；转移校验为纯函数（`MessageState`），并发安全由存储层
CAS（expected → target）保证；重复 ACK 幂等。

### 客户端 outbox（OutboxStatus）

```
PENDING → SENDING → SERVER_ACCEPTED(终态)
        └→ FAILED(nextRetryAt 调度) → PENDING → ... 超阈值 → DEAD_LETTER
```

超时/响应丢失不得推进到 SERVER_ACCEPTED；重试沿用原 messageId。

### 客户端 inbox（ReceiveStatus × ProcessStatus 双维度）

```
ReceiveStatus:  PERSISTED → RECEIVED_ACK_SENT   （SQLite 提交后才可 ACK）
ProcessStatus:  PENDING → PROCESSING → PROCESSED(终态)
                              └→ FAILED → RETRYING → ... 超阈值 → 死信处理
```

## 关键流程

### 上行（线下→线上）
1. 客户端生成唯一 messageId，先写 SQLite outbox（PENDING）。
2. 发送线程 CAS claim（PENDING→SENDING，防并发重复发送）→ HTTPS POST。
3. 服务端校验 → 事务持久化（messageId 唯一约束去重）→ 返回接受结果。
4. 仅收到有效成功响应后置 SERVER_ACCEPTED；超时/断连保持原状态重试。

### 下行（线上→线下）
1. 服务端事务持久化消息 + 投递记录（分配 per-client seq）。
2. SSE 推送 → 客户端校验 → SQLite 事务落盘（唯一约束去重）→ RECEIVED ACK。
3. 业务监听器处理 → PROCESSED ACK / FAILED。
4. 重启/断线：客户端从 inbox 未完成状态恢复；服务端按投递记录补发（findUnfinishedAfterSeq）。

## 重试与退避

| 参数 | 默认值 | 说明 |
|---|---|---|
| 初始退避 | 1s | 指数退避 |
| 最大退避 | 60s | 加随机抖动 |
| 重试上限 | 可配置 | 超限进入死信，不删除 |

- 不可重试错误（认证失败、参数错误）不高速重试。
- 429/5xx/连接超时/读取超时分类处理；遵守 `Retry-After`。
- 过期规则服从业务语义，不为清理队列删除未完成消息。

## 顺序保证（sequenceKey）

默认投递只保证"至少一次"，**不保证顺序**。同一业务实体的状态消息（如订单状态 1 → 2）
如果 2 先到、1 后到，业务状态会被覆盖回退。messageId 幂等去重对此无效——那是两条不同的消息。

需要顺序时，发布方给同实体的消息传同一个 `sequenceKey`（如订单号）：

```java
// 服务端下行
linkora.publish(clientId, "order-status", payload, orderId);
// 客户端上行
client.publish("order-status-updated", payload, orderId);
```

**保证语义**：同一 `clientId` + 同一 `sequenceKey` 下，消息严格按发布顺序投递与处理——
前一条到达终态（PROCESSED / EXPIRED / DEAD_LETTER）之前，后一条不会被推送。
实现为投递查询里的 NOT EXISTS 门禁（SQL 条件，无额外线程/队列），推送路径另有兜底检查。

**边界（必须向业务方明确）**：

| 场景 | 行为 |
|---|---|
| key 为 null / 空串 / 空白 | 归一化为"无键"，完全不参与顺序约束，行为与无键消息一致，不受其他消息阻塞 |
| 不同 key / 不同客户端 | 完全并行，互不影响 |
| 同键队头进死信或过期 | **放行后续**（方案 A）：该条视为放弃，同键后续照常投递；死信有 WARN 日志与状态查询可感知 |
| 同键处理慢 | 该实体的后续消息排队等待——这是顺序的固有代价，键的粒度由业务权衡（粗粒度如"店铺 id"串行强但吞吐低，细粒度如"订单 id"并行好） |

**键的取法（反模式）**：key 完全由业务传入，SDK 不做自动拼接。
- ❌ 拼 clientId——所有消息共用一条车道，全局串行；
- ❌ 拼 messageType——同类型不同实体互相阻塞；
- ❌ 空键随机兜底——每条消息一个新键，等于永远无序还占存储。

**滚动升级安全**：`sequenceKey` 是线路 JSON 可选字段，老版本按"未知字段忽略"处理；
表结构由 schema 迁移自动加列（服务端 v3、客户端 v2），历史数据为 NULL = 无键。

## 断线恢复

- 重连：指数退避 + 随机抖动；心跳超时（默认 2 倍心跳间隔）触发。
- 恢复依据 = 持久化记录 + ACK 状态（**不是**单独依赖 Last-Event-ID）。
- `Last-Event-ID` 采用 per-client 递增 seq，客户端可凭跳号发现消息空洞并发起对账查询。
- 服务端重启、游标失效、消息超保留期：客户端通过 `/linkora/pending` 重新拉取。

## 事务边界（必须向业务方明确）

SQLite（SDK）与业务数据库是独立事务。业务方在业务监听器内提交业务事务后，
SDK 才发送 PROCESSED ACK——保证"业务成功 ⇒ 至少标记处理成功"。
反向（SDK 落盘 ⇒ 业务一定成功）不成立，由重试与死信兜底。
