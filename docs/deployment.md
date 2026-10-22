# 部署指南

## 服务端部署（嵌入宿主容器，零新增中间件）

Linkora 服务端不是一个独立进程，而是一组可嵌入已有 HTTP 服务的组件。
生产部署只需三步：

### 1. 注册三个 Servlet（Servlet 3.1+ 容器）

```java
LinkoraServer server = LinkoraServer.builder()
        .clientAuthenticator(new HmacClientAuthenticator(credentialStore)) // 见 security.md
        .clientRegistry(new JdbcClientRegistry(dataSource))
        .messageRepository(new JdbcMessageRepository(dataSource))
        .deliveryRepository(new JdbcDeliveryRepository(dataSource))
        .cursorRepository(new JdbcClientCursorRepository(dataSource))
        .config(ServerConfig.builder().build())
        // 可选：上行消息业务监听器，客户端上报持久化成功后被回调（Spring 宿主
        // 注册 IngestListener bean 即自动接入，见示例 service/uplink 包）
        .ingestListener(message -> handleUplink(message))
        .build();
server.start();

// contextPath 任意，客户端通过 serverUrl 前缀对齐
context.addServlet(new ServletHolder(
        new SsePushServlet(server.sseSubscribeHandler(), server.sseConnectionRegistry())), "/events");
context.addServlet(new ServletHolder(
        new LinkoraEndpointServlet(server.ingestHttpHandler())), "/messages");
context.addServlet(new ServletHolder(
        new LinkoraEndpointServlet(server.ackHttpHandler())), "/acks");
```

客户端 `serverUrl` = `http(s)://host[:port]/<contextPath前缀>`，默认子路径
`/events`、`/messages`、`/acks`（可用 `ClientConfig` 的
`eventsPath/ingestPath/ackPath` 改）。

### 2. 数据库（复用现有业务库）

- 首次启动调用一次 `JdbcServerSchema.migrate(dataSource)`；
- 迁移为自研版本机制（`lk_schema_version` 表 + 顺序脚本），每个脚本独立事务，
  **任意时刻崩溃重启后自动续跑**，重复调用幂等；
- 表前缀 `lk_*`（lk_client、lk_client_seq、lk_client_cursor、lk_message、
  lk_delivery），不触碰业务表；
- 当前已在 H2 2.2 完成验证；SQL 为方言中立的 ANSI 语法，MySQL 8 /
  PostgreSQL 接入前请先在测试库跑一次 `migrate`（见 compatibility.md）。

### 3. 发放客户端凭据（启用 HMAC 时）

1. `HmacSha256.generateToken()` 生成 256 bit 访问令牌；
2. `HmacSha256.generateToken()` 同样可生成 secret（或自备 ≥32 字节随机值）；
3. 宿主将 `clientId ↔ token ↔ secret` 写入自有凭据表，`CredentialStore`
   实现按 clientId 查询即可；
4. 轮换：CredentialStore 同时接受新旧 token/secret（双凭据并存窗口），
   SDK 侧无需变更。

## 服务端配置参考（ServerConfig）

| 参数 | 默认值 | 说明 |
|---|---|---|
| `allowAnonymous` | false | 缺认证器时是否允许匿名放行；默认拒绝启动 |
| `sweepIntervalMs` | 5000 | 补发/重推扫描周期（≥100） |
| `pushBatchSize` | 100 | 单次扫描每客户端最多处理的投递记录数 |
| `maxDeliveryRetries` | 10 | FAILED ACK 累计次数上限，超过进入死信 |
| `retryBackoffBaseMs/MaxMs` | 2000 / 300000 | 失败重试指数退避 |
| `pushPacingBaseMs/MaxMs` | 5000 / 60000 | 在线客户端重推节奏（防风暴） |
| `messageSizeLimitBytes` | 1MB | 上行 **HTTP body** 上限（≤16MB）。原始 payload ≈ 该值 × 0.75（Base64 膨胀 4/3 + 信封开销），默认约 786KB。Servlet 读取闸门直接取此值，不再另设默认上限；要传更大的 payload 只改这一处 |
| `retentionEnabled` | true | 是否清理终态历史数据 |
| `retentionMs` | 7 天 | 终态（PROCESSED/EXPIRED）记录保留时长，超期分批删除 |
| `retentionBatchSize` | 500 | 单次清理条数上限（避免长事务） |
| `expirySweepBatchSize` | 500 | 单次"离线过期推进"条数上限 |

> 过期推进与保留策略不依赖客户端在线：`DEAD_LETTER` 与所有未完成记录永不自动删除，
> 需要人工介入或宿主自行归档。

### SSE 写出（LinkoraServerProperties 同名配置项）

| 参数 | 默认值 | 说明 |
|---|---|---|
| `sseQueueCapacity` | 1024 | 每连接待写帧数上限，满即断开该连接（重连补发恢复） |
| `sseWriteStallTimeoutMs` | 15000 | 单次 flush 阻塞超过该时长判定连接不可用并关闭（0 关闭看门狗） |
| `sseWriterThreads` | 16 | 写线程池大小，按"可能同时卡死的连接数"估算 |

> 除帧数上限外还有一道**字节闸门**（代码常量 8MB/连接）：帧数挡不住大消息，
> 1MB payload 的帧约 1.37MB，1024 帧即 1.4G。超过即断开，消息仍在 `lk_delivery`。

## 客户端部署

- 引入 `linkora-client`（自带 OkHttp 传输与 SQLite 本地存储）；
- SQLite 库文件位置：`new SqliteClientStore(Path)`（父目录自动创建）；
  **每个客户端实例独占一个库文件**，多实例共享同一文件会被 WAL 锁竞争拖慢；
- 磁盘要求：消息积压上限 ≈ 未送达消息量，按业务峰值估算；
  已完成记录默认保留 7 天后自动清理；
- 客户端参数见 [ClientConfig](../linkora-client/src/main/java/com/linkora/client/ClientConfig.java)
  javadoc（超时、批次、重试、保留期均有界校验，默认值适合多数场景）。

## 优雅关闭

关闭顺序：先停 HTTP 容器（停止接新请求）→ `server.close()` /
`client.close()`。两个 close 均幂等：

- 服务端：停止扫描与推送调度，在途状态已全部持久化；
- 客户端：停止 SSE/上报调度，outbox/inbox 状态已全部持久化，
  **进程被 kill -9 也安全**，重启后自动恢复续跑。

## 健康检查与观测

- `server.isRunning()`：存活探测；
- `server.getBacklog(clientId)`：某客户端积压量；
- `server.getMessageStatus(messageId)`：单条消息投递状态与全部投递记录；
- `server.sweepOnce()`：手动触发一次补发扫描（运维补投后立即可用）；
- 日志：SLF4J 门面，实现交给宿主；日志含 messageId/clientId/traceId，
  不含凭据与未脱敏负载。

## 升级与备份

- **升级**：新版本若含 schema 变更，启动时 `migrate` 自动增量应用；
  先在测试库验证迁移脚本，再对生产库做常规备份后升级；
- **备份**：按普通业务表备份 `lk_*` 五张表；客户端侧备份 SQLite
  `.db`（及 WAL）文件；恢复后各端按持久化状态自动续跑，无需人工对账；
- **死信处置**：`getMessageStatus` 看到 DEAD_LETTER 后，修复业务原因，
  用同一 messageType 重新 `publish` 即可产生新投递（原记录保留作审计）。
