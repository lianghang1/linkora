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

## 断线恢复

- 重连：指数退避 + 随机抖动；心跳超时（默认 2 倍心跳间隔）触发。
- 恢复依据 = 持久化记录 + ACK 状态（**不是**单独依赖 Last-Event-ID）。
- `Last-Event-ID` 采用 per-client 递增 seq，客户端可凭跳号发现消息空洞并发起对账查询。
- 服务端重启、游标失效、消息超保留期：客户端重连后按未完成投递记录重新拉取
  （对应 Java API `LinkoraServer.getBacklog()` / 客户端 inbox 续跑）。
- 补发与扫描的批次**只装"当前可投递"的记录**：终态、死信、未到重试时间的记录
  都不占名额。否则一批死信会把批次吃满，让后面的活消息永久饿死。
- 周期扫描带轮转游标（扫到批次末尾就下次接着扫，扫到尾回卷到 0），
  避免"队头一直不 ACK"把新消息挡住。

## 事务边界（必须向业务方明确）

SQLite（SDK）与业务数据库是独立事务。业务方在业务监听器内提交业务事务后，
SDK 才发送 PROCESSED ACK——保证"业务成功 ⇒ 至少标记处理成功"。
反向（SDK 落盘 ⇒ 业务一定成功）不成立，由重试与死信兜底。
