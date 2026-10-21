# 故障排查

## 客户端侧

### 消息一直停在 PENDING

- **最常见原因：未调用 `client.start()`**。publish 只写本地 outbox，
  后台发送线程启动后才会触发 drain；
- 其次：进程重启后未 start，恢复扫描也不会运行；
- 排查：`clientStore.findOutbox(messageId)` 查看状态与 `lastError`。

### 消息进入 DEAD_LETTER（outbox）

- 服务端返回 401/403/404/413 等不可重试 4xx，或重试次数耗尽
  （`outboxMaxRetries` 默认 12）；
- 处置：修复原因后用同一 messageType 重新 `publish`；
- 408/429/5xx 视为可重试，不会因此进入死信。

### 业务监听器没有收到消息

1. 确认 `addListener(messageType, listener)` 的 topic 与服务端
   `publish(clientId, messageType, ...)` 的 messageType 完全一致；
2. SSE 是否已连接：查看日志中 SSE 状态变化；
3. 消息可能仍在服务端补发周期内（`sweepIntervalMs` 默认 5s）；
4. 同一 messageId 已处理过（inbox 去重，幂等设计行为）。

### 消息重复到达

**这是至少一次投递的预期语义**：ACK 丢失/进程崩溃后服务端会补推。
业务侧用 `LinkoraReceivedMessage.getMessageId()` 做幂等键即可；
客户端 inbox 已按 messageId 去重，同一条消息不会重复回调。

### SQLite 报错 `NativeDB` 初始化失败 / `UnsatisfiedLinkError`

classpath 中存在**多个版本**的 sqlite-jdbc，native 库加载冲突。
保证宿主依赖树中 sqlite-jdbc 版本唯一（`mvn dependency:tree | findstr sqlite`）。

### 时钟相关 401（HMAC）

- 客户端与服务端系统时间偏差超过 ±5 分钟（默认窗口）会持续认证失败；
- 客户端使用 NTP 对时，或确认两端时区无关（签名用 epoch 毫秒）；
- 连续失败 5 次后触发指数锁定（最长 15 分钟，返回 429 + RATE_LIMITED）；
  修复时钟后可等锁定自动解除或重启服务端（锁定计数纯内存）。

## 服务端侧

### 端口占用 / 地址冲突

- 示例默认 8080：`java` 侧报 `Address already in use` 时换端口或释放占用；
- 生产嵌入宿主容器时 Linkora 不监听端口，无此问题。

### 订阅被 401 / 429 拒绝

- 401 `AUTH_FAILED`：token/签名/时间戳/nonce 任一错误（统一错误码防枚举），
  服务端日志按 clientId 关联定位；
- 429 `RATE_LIMITED`：该 clientId 触发失败锁定，等待解除；
- 403：客户端被禁用（`ClientRegistration.enabled=false`）或注册不存在。

### 上报返回 413

消息体超过 `messageSizeLimitBytes`（默认 1MB）或端点 Servlet 硬上限 2MB。
调大 `ServerConfig.messageSizeLimitBytes`（≤16MB）。

### 上报返回 412

请求缺少或版本不匹配 `X-Linkora-Protocol: 1` 头——通常是非 Linkora 客户端
在裸调端点。

### 积压持续增长

1. `server.getBacklog(clientId)` 确认是哪个客户端积压；
   **单客户端离线不阻塞其他客户端**（按 clientId 独立队列）；
2. 该客户端是否长期离线：离线消息持久化等待其上线；
3. 客户端在线但投递慢：检查该客户端 `sweepIntervalMs` 期间 ACK 回报是否
   正常（PROCESS 卡住→业务监听器是否漏调 ackProcessed/ackFailed）；
4. 运维补投后可调 `server.sweepOnce()` 立即触发扫描。

### 服务端重启后 nonce/锁定状态丢失

设计行为：`FailureLockout` 与 `NonceCache` 为纯内存实现。重启清零不影响
安全目标（爆破者无法从中获益：token 未变、时间窗口照常校验、消息投递
持久化不受影响）。

## 通用

### 控制台中文乱码（Windows）

示例输出为 UTF-8，PowerShell 控制台默认 GBK 显示所致，
`chcp 65001` 后重跑即可；不影响功能与持久化数据。

### 日志没有任何输出

SLF4J 门面缺少实现：宿主加入任一实现（logback / log4j2 / slf4j-simple）。
缺实现时 SLF4J 退化为 NOP，SDK 自身不报错。

### 死消息/历史数据清理

- 客户端：SERVER_ACCEPTED outbox 与已完成 inbox 按
  `outboxRetentionMs/inboxRetentionMs`（默认 7 天）自动清理；
- 服务端：`lk_message/lk_delivery` 由宿主按业务保留策略归档清理
  （先归档再删除，勿跳过备份）。
