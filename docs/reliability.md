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

## 顺序语义（opt-in）

默认**不保证顺序**：重试、补发、pacing 都会让后发布的消息先到达。

需要顺序时给消息带 `sequenceKey`（订单号、账户号、设备号等任意业务实体）：

- **下行**：服务端推送门禁——同 `(clientId, sequenceKey)` 下，seq 更小的记录未到终态
  （PROCESSED / EXPIRED）时，后续记录一律不推，且在 SQL 里过滤（不占补发批次名额）；
- **上行**：客户端发送门禁——同键更早入队且未终结的消息未完成时，后一条不发送；
  这条同时修掉了"FAILED 批在 PENDING 批之后重发导致旧消息被超车"的问题；
- **客户端处理**：单 worker 串行 + 按 key 兜底校验，前一条终结后才派发同键下一条。

边界（必须知道）：

| 情况 | 行为 |
|---|---|
| 前一条进死信（重试耗尽） | **放行**后续，否则该实体永久卡死 |
| 前一条过期（EXPIRED） | 放行后续（不会送达，业务需容忍缺口） |
| 键为 null | 退化为原有"尽力投递"，不阻塞 |
| 跨键 | 完全并行，互不影响 |
| 同键处理慢 | 该实体后续消息延迟（这是顺序的代价），其他实体不受影响 |

### 键怎么取（重要）

- **粒度 = 业务实体**：订单号、账户号、设备号。不要用"门店号"这类过大的粒度，
  那会把无关消息串行化；也不要用消息 ID 这类"每条都不同"的值，那等于没有顺序键。
- **不要自己加前缀维度**：SDK 的分组维度已经是 `(clientId, sequenceKey)`，
  拼 clientId 是冗余的；拼 messageType 会**破坏同一实体跨事件类型的顺序**
  （`order-created` / `order-paid` 会被拆进不同车道）。
- **推荐命名 `域名:主键`**（如 `order:20261022-0001`、`refund:R-88`）：避免不同模块
  用同一个裸主键撞车，日志也便于检索。长度上限 128，注意别超。
- **空键不要用随机值兜底**：随机值会让每条消息独占车道，顺序保证静默失效，
  且日志无法检索。要关掉顺序就别传键（null / 空白等价于"不参与约束"）。

注意：顺序保证**不替代业务幂等**，也不保证"业务侧一定按版本应用"——跨系统写入、
人工修数仍需业务侧用版本号兜底。

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
- `Last-Event-ID` 是**传输层续传提示**，不是可靠性依据：补发只推未完成记录，已 ACK 的
  不会再推，所以重连后出现 seq 跳号属正常现象，客户端仅记日志、不做对账。
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

## 已知缺口（本版明确接受）

以下四条是当前版本明确接受的边界，不是遗漏。改动前请先评估收益与复杂度。

| 缺口 | 触发条件 | 后果 |
|---|---|---|
| **孤儿消息** | `publish` 在 `lk_message` 落库与 `lk_delivery` 初始化之间崩溃，或 `init` 抛异常 | 消息有正文但无投递记录，扫描（`findUnfinishedAfterSeq`）不可见，**永久不投递且无告警**。业务用同一 messageId 重发时会走幂等分支补建投递记录；只发一次则丢 |
| **无监听器静默完成** | `messageType` 拼写错误，或监听器在 `client.start()` 之后才注册 | 消息被直接标记 `PROCESSED`，业务从未执行，**没有日志**（`processInbox` 的"无监听器"分支） |
| **同键并发发布乱序** | 两个线程同时 `publish` 同一 clientId + 同一 `sequenceKey` | seq 分配与投递记录初始化不在同一事务，seq 较大的可能先进入门禁判断（此时前一条尚未 init，查不到），导致**顺序保证失效**。单线程串行发布不受影响 |
| **监听器卡死阻塞通道** | 业务监听器死循环或长时间阻塞 | worker 为单线程且无执行超时，后续消息全部排队；消息不丢（服务端未收到 ACK 会重投），但表现为"客户端收不到" |

另有两项资源性缺口：死信（服务端 `lk_delivery`、客户端 `lk_outbox`）永不自动清理；
客户端下行落库与 ACK 发送共用单线程，ACK 接口变慢会推迟落库（不影响最终投递）。
