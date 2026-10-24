# Linkora API 参考：服务端与客户端管理接口

> 全部管理接口返回**人类可读视图**：时间一律 `yyyy-MM-dd HH:mm:ss`（无值显示 `—`），
> payload 一律 UTF-8 文本预览（超 200 字符截断、二进制只标注字节数，**绝不输出 Base64**），
> 状态附中文描述。SDK 只提供方法、不做鉴权；HTTP 暴露由宿主实现并**必须加访问控制**
> HTTP 透出示例见 `examples/*/controller/AdminController.java`。

---

## 一、服务端管理 API（`LinkoraServer`）

### 1.1 查询类

| 方法 | 返回 | 说明 |
|---|---|---|
| `getClientOverviews()` | `List<ClientOverviewView>` | 全部注册客户端概览：在线状态、连接数、未完成数、死信数、注册时间 |
| `getBacklog(clientId)` | `BacklogInfo` | 单客户端：未完成投递数 + 是否有活跃 SSE 连接 |
| `listUnfinishedDeliveries(clientId, limit)` | `List<DeliveryView>` | 未终结投递明细（含重试中与死信） |
| `listDeadLetters(clientId, limit)` | `List<DeliveryView>` | 死信列表（原因在 lastError） |
| `listMessages(clientId?, messageType?, beginTime?, endTime?, page, size)` | `List<MessageView>` | 消息列表多条件筛选 + 分页（created_at 倒序），每条附各目标投递状态中文摘要；beginTime/endTime 为 epoch 毫秒（含边界） |
| `getMessageDetail(messageId)` | `MessageDetailView` | 完整 payload 明文 + 全部投递记录视图；消息不存在抛 `IllegalArgumentException` |
| `getMessageStatus(messageId)` | `Optional<MessageDeliveryStatus>` | 原始投递记录（程序化对账用，非人类可读） |
| `getDailyStats(days)` | `DailyStatsView` | 最近 N 天每日发布量 + 投递状态分布（中文状态名） |

**入参边界**：`limit` 自动收敛到 1~500；`page` 从 1 起；`size` 默认 20。

### 1.2 处置类

| 方法 | 返回 | 行为 |
|---|---|---|
| `disconnectClient(clientId)` | `int` | 踢掉该客户端的全部 SSE 连接（处置假在线）；客户端自动重连按 Last-Event-ID 补发，不丢数据。返回断开的连接数 |
| `retryDeadLetter(clientId, messageId)` | `boolean` | 死信 → RETRYING 并立即重推 |
| `skipMessage(clientId, messageId)` | `boolean` | 人工跳过：→ EXPIRED 终态，lastError 标注"人工跳过"，同键后续立即放行 |
| `forceTransition(clientId, messageId, targetState, reason)` | `boolean` | 通用人工状态变更：状态机校验合法性（非法转移抛 `IllegalArgumentException`），reason 写入 lastError 留痕；目标为 RETRYING 时自动触发重推 |
| `enableClient(clientId)` / `disableClient(clientId)` | `boolean` | 运行时启停：禁用后拒收新消息、拒绝接入 |
| `retryAllDeadLetters(clientId, limit)` | `int` | 批量重放死信，返回成功条数 |
| `skipAllDeadLetters(clientId, limit)` | `int` | 批量跳过死信（全部 EXPIRED 留痕） |
| `sweepOnce()` | `void` | 手动触发一轮补发扫描（运维/测试用） |
| `cleanupOnce()` | `int` | 立即执行一次保留期清理（仅删除已终态且超期的记录，死信豁免）；返回删除行数；`retentionDays=0` 时返回 0 |

**skipMessage 竞态边界（重要）**：skip 只保证"**之后**不再投递"，**无法撤回已经写出的
在途推送帧**——若消息在跳过生效瞬间已被推给在线客户端（客户端照常处理，其 PROCESSED
ACK 因终态被静默拒绝），会出现"已跳过但客户端收到一次"的现象，这是 at-least-once
语义下的固有竞态，不是缺陷。规避：对卡住的消息（RETRYING/死信）执行跳过（此时无
在途帧），或确认客户端离线后再跳过。

### 1.3 指令通道（远程触发客户端运营操作）

指令走 SSE `ctrl` 事件（与业务 `msg`、心跳 `hb` 并列），**不落库、不重试、不进客户端
业务队列**——仅对在线客户端实时生效，未送达返回 0 由运维再次触发。指令白名单见
`com.linkora.core.admin.ClientCommands`，未知指令服务端拒绝、客户端忽略。

| 方法 | 返回 | 说明 |
|---|---|---|
| `sendClientCommand(clientId, command, params)` | `int` | 下发指令（白名单校验）；返回送达的连接数（0 = 离线） |
| `retryClientOutboxDeadLetter(clientId, messageId)` | `int` | 远程触发客户端重发其上行死信 |
| `discardClientOutboxDeadLetter(clientId, messageId, reason)` | `int` | 远程触发客户端放弃其上行死信 |
| `pingClient(clientId)` | `int` | 指令通道连通性探测 |
| `queryClientOutbox(clientId, status?, limit)` | `String` | 远程查询设备本地 outbox，返回 queryId |
| `getCommandResult(queryId)` | `Map`（可 null） | 读取远程查询结果（客户端经上行回传；内存暂存有界 100 条）。null 的三种情况：未下发 / 客户端未回传 / 结果已被淘汰 |

指令白名单：

| 指令 | 参数 | 客户端行为 |
|---|---|---|
| `RETRY_OUTBOX_DEAD_LETTER` | `messageId` | 上行死信重发（DEAD_LETTER → PENDING，重试计数清零） |
| `DISCARD_OUTBOX_DEAD_LETTER` | `messageId`, `reason` | 上行死信放弃留痕 |
| `QUERY_OUTBOX` | `status?`, `limit`, `queryId` | 查询本地 outbox 并经上行回传结果 |
| `PING` | — | 连通性探测 |

---

## 二、管理视图字段说明

### ClientOverviewView

| 字段 | 类型 | 说明 |
|---|---|---|
| `clientId` | String | 客户端标识 |
| `enabled` | boolean | 是否启用 |
| `online` | boolean | 是否有活跃 SSE 连接 |
| `connectionCount` | int | 活跃连接数 |
| `unfinishedCount` | long | 未终结投递数（含重试中与死信） |
| `deadLetterCount` | long | 死信数 |
| `createdAt` | String | 注册时间（格式化） |

### DeliveryView（未完成/死信/投递明细通用）

| 字段 | 类型 | 说明 |
|---|---|---|
| `messageId` / `clientId` | String | 标识 |
| `messageType` | String | 消息类型 |
| `seq` | long | 投递序号（同客户端单调递增） |
| `stateText` | String | 状态中文描述（见下表） |
| `retryCount` | int | 已重试次数 |
| `nextRetryAt` | String | 下次重试时间（格式化，无则 `—`） |
| `lastError` | String | 最后错误（含 reason 留痕） |
| `payloadPreview` | String | payload 文本预览（超长截断；二进制标注字节数） |
| `payloadSize` | int | payload 字节数 |
| `sequenceKey` | String | 顺序键（无则 null） |
| `createdAt` / `updatedAt` | String | 创建/最后更新时间（格式化） |

### MessageView / MessageDetailView

| 字段 | 说明 |
|---|---|
| `messageId` / `clientId` / `messageType` | 标识 |
| `payloadPreview` | 文本预览（MessageView，200 字符截断） |
| `payload` | 完整明文（仅 MessageDetailView；二进制标注字节数） |
| `sequenceKey` | 顺序键 |
| `expiresAt` / `traceId` | 过期时间（格式化）/ 链路追踪 |
| `deliverySummary` | 各目标投递状态中文摘要（如 `demo-client:处理成功（终态)`） |
| `deliveries` | 投递记录列表（仅 MessageDetailView，元素为 DeliveryView） |

### DailyStatsView

| 字段 | 说明 |
|---|---|
| `publishedPerDay` | `List<{date: "yyyy-MM-dd", count}>` 每日发布量（最近 N 天，含 0） |
| `deliveryStateDistribution` | `Map<中文状态, 数量>` 当前投递状态分布 |

### 状态中文对照表

| 投递状态（服务端） | 中文 |
|---|---|
| CREATED | 已创建 |
| ACCEPTED | 已接受，等待推送 |
| RECEIVED | 客户端已接收 |
| RETRYING | 重试中 |
| DEAD_LETTER | 死信，等待人工介入 |
| PROCESSED | 处理成功（终态） |
| EXPIRED | 已过期/已跳过（终态） |

| 上行队列状态（客户端 outbox） | 中文 |
|---|---|
| PENDING | 待发送 |
| SENDING | 发送中 |
| SERVER_ACCEPTED | 服务端已确认（终态） |
| FAILED | 发送失败，等待重试 |
| DEAD_LETTER | 死信，等待人工介入（终态） |

| 下行收件状态（客户端 inbox，两级） | 中文 |
|---|---|
| PERSISTED / RECEIVING | 已落库 / 接收中 |
| RECEIVED | 已确认接收 |
| PENDING / PROCESSING | 待处理 / 处理中 |
| PROCESSED | 处理成功（终态） |
| FAILED | 处理失败，等待重试 |
| DEAD_LETTER | 死信，等待人工介入（终态） |

---

## 三、客户端管理 API（`LinkoraClient`）

### 3.1 查询

| 方法 | 返回 | 说明 |
|---|---|---|
| `listOutbox(status?, limit)` | `List<OutboxView>` | 上行队列明细（全部或按状态筛） |
| `listInbox(processStatus?, limit)` | `List<InboxView>` | 下行收件明细（接收/处理两级状态） |
| `listOutboxDeadLetters(limit)` | `List<OutboxDeadLetterView>` | 上行死信列表 |
| `getSseState()` / `getState()` | 枚举 | SSE 连接状态 / 客户端生命周期状态 |
| `store.findOutbox / findInbox` 等 | 原始实体 | ClientStore SPI 直查（程序化用） |

**OutboxView / InboxView 字段**：`messageId`、`messageType`、`payloadPreview`、
`payloadSize`、`statusText`（中文；inbox 为接收/处理两级）、`retryCount`、
`lastError`、`createdAt`、`updatedAt`（时间均格式化）。

### 3.2 处置

| 方法 | 返回 | 行为 |
|---|---|---|
| `retryOutboxDeadLetter(messageId)` | `boolean` | 上行死信重发：DEAD_LETTER → PENDING，重试计数清零，立即触发发送 |
| `discardOutboxDeadLetter(messageId, reason)` | `boolean` | 放弃留痕：lastError 记录"人工放弃: reason" |

**注意**：重发需要设备网络可用；上行死信产生后不会自动复活（进程重启亦然），
无人工介入的需求场景时无需处理。

### 3.3 既有客户端 API 速查（非管理类）

| API | 说明 |
|---|---|
| `publish(type, payload)` / `publish(type, payload, sequenceKey)` | 上行发布（带键 = 同键严格按序被服务端接收） |
| `addListener(type, listener)` | 注册业务监听器；`message.getSequenceKey()` 可读顺序键 |
| `addOutboxListener(...)` | 上行状态变更实时回调 |
| `uplinkPublisher.awaitServerAccepted(messageId, timeout)` | 阻塞等待某条上行被服务端接受 |

---

## 四、HTTP 暴露示例

Boot/MVC 示例已内置演示端点（**生产必须自行加鉴权**）：

```
GET  /api/admin/clients                       客户端概览
GET  /api/admin/unfinished?clientId=          未终结明细
GET  /api/admin/dead-letters?clientId=        死信列表
GET  /api/admin/messages?clientId=&messageType=&page=&size=   消息列表
GET  /api/admin/message-detail?messageId=     消息详情
GET  /api/admin/stats?days=7                  运营统计
POST /api/admin/disconnect?clientId=          踢连接
POST /api/admin/retry-dead-letter?clientId=&messageId=    死信重放
POST /api/admin/skip?clientId=&messageId=     跳过
POST /api/admin/force-transition?clientId=&messageId=&target=&reason=  状态变更
POST /api/admin/enable | disable?clientId=    启停
POST /api/admin/retry-all-dead-letters | skip-all-dead-letters?clientId=  批量
POST /api/admin/client-command?clientId=&command=...     下发指令
POST /api/admin/query-client-outbox?clientId=...         远程查询
GET  /api/admin/command-result?queryId=       读取远程查询结果
```

非法参数 / 非法状态转移返回 400（error 字段说明原因）；存储异常返回 500。

---

## 五、使用建议

1. **日常巡检**：定时调 `getClientOverviews`，对 `unfinishedCount` 持续增长或
   `ACCEPTED` 状态停留超过数分钟的消息告警（ACCEPTED 长期不动 = 推送链路异常信号）
2. **死信处置流程**：`listDeadLetters` 查看原因 → 业务判断可重放则 `retryDeadLetter`、
   可放弃则 `skipMessage`（都留痕）；积压多时用批量接口
3. **远程运维**：设备无人值守时用指令通道（`retryClientOutboxDeadLetter` /
   `queryClientOutbox`）；指令只对在线设备生效，离线时设备上线后再触发
4. **审计**：所有处置方法的 reason 写入 lastError 持久化；forceTransition 的 reason
   建议记录操作人与工单号
